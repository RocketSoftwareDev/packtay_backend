package ec.paktay.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;

class PasswordPinServiceTest {
    JdbcTemplate db;
    KeycloakIdentityService identities;
    PasswordMailService mail;
    PasswordPinService service;
    Clock clock;
    long now;
    String id = "11111111-1111-1111-1111-111111111111";
    Map<String,Object> user;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("password-schema.sql")).execute(ds);
        db = new JdbcTemplate(ds);
        db.execute("create table app_users(id uuid, email varchar(320), status varchar(16))");
        db.execute("create table password_temporary(user_id uuid primary key, expires_at timestamp, created_by uuid, created_at timestamp)");
        identities = mock(KeycloakIdentityService.class); mail = mock(PasswordMailService.class);
        clock = mock(Clock.class); now = Instant.parse("2026-09-10T00:00:00Z").toEpochMilli();
        when(clock.millis()).thenAnswer(call -> now);
        user = Map.of("id", id, "email", "user@example.com", "enabled", true);
        when(identities.findByEmail("user@example.com")).thenAnswer(call -> user);
        when(identities.findById(id)).thenAnswer(call -> user);
        service = new PasswordPinService(db, new TransactionTemplate(new DataSourceTransactionManager(ds)), identities, mail, clock);
    }
    String request() {
        service.requestReset(" USER@example.com ");
        var pin = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendPin(eq("user@example.com"), pin.capture(), eq("RESET"));
        return pin.getValue();
    }
    @Test void registeredEmailReceivesSixDigitsAndOnlyHashIsStored() {
        String pin = request(); assertTrue(pin.matches("[0-9]{6}"));
        assertNotEquals(pin, db.queryForObject("select pin_hash from password_pins", String.class));
    }
    @Test void unknownAccountGetsNoEmailAndBlockedAccountIsToldToContactSupport() {
        service.requestReset("nobody@example.com");
        when(identities.findByEmail("disabled@example.com")).thenAnswer(call -> Map.of("enabled", false));
        var error = assertThrows(ec.paktay.auth.exception.CodedException.class, () -> service.requestReset("disabled@example.com"));
        assertEquals("ACCOUNT_BLOCKED", error.code());
        verifyNoInteractions(mail);
    }
    @Test void completedResetReplacesTemporaryPasswordAndClearsLockout() {
        db.update("insert into password_temporary(user_id, expires_at) values (?, current_timestamp)", UUID.fromString(id));
        service.completeReset("user@example.com", service.verifyReset("user@example.com", request()), "NewPassword123!");
        assertEquals(0, db.queryForObject("select count(*) from password_temporary", Integer.class));
        verify(identities).clearBruteForce(id);
    }
    @Test void localProfileFallbackMustMatchCurrentKeycloakEmail() {
        when(identities.findByEmail(anyString())).thenReturn(null);
        db.update("insert into app_users values (?,?,?)", UUID.fromString(id), "user@example.com", "ACTIVE");
        request();
        now += 61000;
        when(identities.findById(id)).thenAnswer(call -> Map.of("id", id, "email", "other@example.com", "enabled", true));
        service.requestReset("user@example.com");
        verify(mail, times(1)).sendPin(anyString(), anyString(), anyString());
    }
    @Test void fiveFailedAttemptsRemainCommitted() {
        String pin = request();
        String wrong = pin.equals("000000") ? "111111" : "000000";
        for (int i=0;i<5;i++) assertThrows(IllegalArgumentException.class, () -> service.verifyReset("user@example.com", wrong));
        assertThrows(IllegalArgumentException.class, () -> service.verifyReset("user@example.com", pin));
        assertEquals(5, db.queryForObject("select attempts from password_pins", Integer.class));
        verify(identities, never()).replacePassword(anyString(), anyString(), anyBoolean());
    }
    @Test void successfulPinCannotBeReused() {
        String pin = service.verifyReset("user@example.com", request());
        service.completeReset("user@example.com", pin, "NewPassword123!");
        assertThrows(IllegalArgumentException.class, () -> service.completeReset("user@example.com", pin, "OtherPassword123!"));
        verify(identities, times(1)).replacePassword(id, "NewPassword123!", false);
    }
    @Test void expiresAfterFifteenMinutes() {
        String pin = request(); now += 900000;
        assertThrows(IllegalArgumentException.class, () -> service.verifyReset("user@example.com", pin));
    }
    @Test void cooldownAndHourlyLimitApply() {
        request(); service.requestReset("user@example.com");
        verify(mail, times(1)).sendPin(anyString(), anyString(), anyString());
        for (int i=0;i<5;i++) {now += 61000; service.requestReset("user@example.com");}
        verify(mail, times(5)).sendPin(anyString(), anyString(), anyString());
        now += 3600000; service.requestReset("user@example.com");
        verify(mail, times(6)).sendPin(anyString(), anyString(), anyString());
    }
    @Test void resendInvalidatesPreviousHash() {
        request(); String oldHash = db.queryForObject("select pin_hash from password_pins", String.class);
        now += 61000; service.requestReset("user@example.com");
        assertNotEquals(oldHash, db.queryForObject("select pin_hash from password_pins", String.class));
    }
    @Test void profileRequiresItsOwnPurposeAndIdentity() {
        String resetPin = request();
        assertThrows(IllegalArgumentException.class, () -> service.verifyChange(id, resetPin));
        service.requestChange(id);
        var pin = ArgumentCaptor.forClass(String.class);
        verify(mail).sendPin(eq("user@example.com"), pin.capture(), eq("CHANGE"));
        service.completeChange(id, service.verifyChange(id, pin.getValue()), "NewPassword123!");
        verify(identities).replacePassword(id, "NewPassword123!", false);
    }
    @Test void upstreamFailureDoesNotConsumePin() {
        String pin = service.verifyReset("user@example.com", request());
        doThrow(new IllegalStateException("Unavailable")).doNothing().when(identities).replacePassword(id, "NewPassword123!", false);
        assertThrows(IllegalStateException.class, () -> service.completeReset("user@example.com", pin, "NewPassword123!"));
        service.completeReset("user@example.com", pin, "NewPassword123!");
    }
    @Test void mailFailureRollsBackRequest() {
        doThrow(new IllegalStateException("SMTP unavailable")).when(mail).sendPin(anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> service.requestReset("user@example.com"));
        assertEquals(0, db.queryForObject("select count(*) from password_pins", Integer.class));
    }

    @Test void verifyDoesNotChangePasswordAndConsumesPin() {
        String pin = request();
        String token = service.verifyReset("user@example.com", pin);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(token, db.queryForObject("select token_hash from password_pins", String.class));
        verify(identities, never()).replacePassword(anyString(), anyString(), anyBoolean());
        assertThrows(IllegalArgumentException.class, () -> service.verifyReset("user@example.com", pin));
        assertThrows(IllegalArgumentException.class, () -> service.completeReset("user@example.com", pin, "NewPassword123!"));
        service.completeReset("user@example.com", token, "NewPassword123!");
    }
    @Test void tokenExpiresInTenMinutes() {
        String token = service.verifyReset("user@example.com", request()); now += 600000;
        assertThrows(IllegalArgumentException.class, () -> service.completeReset("user@example.com", token, "NewPassword123!"));
    }
    @Test void resendInvalidatesVerifiedToken() {
        String token = service.verifyReset("user@example.com", request()); now += 61000;
        service.requestReset("user@example.com");
        assertThrows(IllegalArgumentException.class, () -> service.completeReset("user@example.com", token, "NewPassword123!"));
    }
    @Test void tokenCannotBeUsedForAnotherPurposeOrAccount() {
        String token = service.verifyReset("user@example.com", request());
        assertThrows(IllegalArgumentException.class, () -> service.completeChange(id, token, "NewPassword123!"));
        assertThrows(IllegalArgumentException.class, () -> service.completeReset("other@example.com", token, "NewPassword123!"));
    }
}
