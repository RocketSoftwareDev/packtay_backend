package ec.paktay.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import ec.paktay.auth.dto.TokenResponse;
import ec.paktay.auth.exception.CodedException;
import ec.paktay.auth.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class TemporaryPasswordServiceTest {
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final AdminActor ADMIN = new AdminActor("22222222-2222-2222-2222-222222222222", "admin@paktay.app");
    private static final TokenResponse TOKENS = new TokenResponse("access", "refresh", 300, 1800, "Bearer", null);

    private KeycloakIdentityService identities;
    private PasswordMailService mail;
    private JdbcTemplate db;
    private TemporaryPasswordService service;

    @BeforeEach
    void setUp() {
        identities = mock(KeycloakIdentityService.class);
        mail = mock(PasswordMailService.class);
        db = mock(JdbcTemplate.class);
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("access")).thenReturn(Jwt.withTokenValue("access").header("alg", "RS256").subject(USER).build());
        // findById devuelve Map<?, ?>: con thenReturn(Map.of(...)) Java no infiere el tipo.
        doReturn(Map.of("id", USER, "email", "ana@paktay.app", "enabled", true)).when(identities).findById(USER);
        service = new TemporaryPasswordService(identities, mail, mock(AdminAuditWriter.class), db, decoder, new AccountAccess(db, identities));
    }

    private void pending(Boolean vigente) {
        when(db.queryForList(anyString(), eq(Boolean.class), eq(USER))).thenReturn(vigente == null ? List.of() : List.of(vigente));
    }

    @Test
    void laContraseniaGeneradaCumpleLaPoliticaYElFormatoDelCorreo() {
        for (int i = 0; i < 200; i++) {
            String password = service.generate();
            assertTrue(password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{16}$"), password);
            assertTrue(password.matches("[A-Za-z0-9!#%*+=?@_-]{12,64}"), password);
        }
    }

    @Test
    void enviarCambiaLaContraseniaCierraSesionesQuitaElBloqueoYMandaElCorreo() {
        service.issue(USER, ADMIN);
        verify(identities).replacePassword(eq(USER), anyString(), eq(false));
        verify(identities).clearBruteForce(USER);
        verify(identities).logout(USER);
        verify(mail).sendTemporaryPassword(eq("ana@paktay.app"), anyString(), eq(24));
    }

    @Test
    void noSeEnviaALaCuentaPropiaNiAUnaBloqueada() {
        assertThrows(ConflictException.class, () -> service.issue(ADMIN.id(), ADMIN));
        doReturn(Map.of("id", USER, "email", "ana@paktay.app", "enabled", false)).when(identities).findById(USER);
        assertThrows(ConflictException.class, () -> service.issue(USER, ADMIN));
        verify(identities, never()).replacePassword(anyString(), anyString(), anyBoolean());
    }

    @Test
    void siElCorreoFallaSeAvisaParaRepetir() {
        doThrow(new RuntimeException("smtp")).when(mail).sendTemporaryPassword(anyString(), anyString(), anyInt());
        assertThrows(IllegalStateException.class, () -> service.issue(USER, ADMIN));
    }

    @Test
    void loginConTemporalVigentePideCambiarla() {
        pending(true);
        assertEquals(Boolean.TRUE, service.afterMobileLogin(TOKENS).passwordChangeRequired());
    }

    @Test
    void loginSinTemporalNoCambiaNada() {
        pending(null);
        assertNull(service.afterMobileLogin(TOKENS).passwordChangeRequired());
    }

    @Test
    void loginConTemporalVencidaSeRechazaYCierraLaSesion() {
        pending(false);
        CodedException error = assertThrows(CodedException.class, () -> service.afterMobileLogin(TOKENS));
        assertEquals("TEMPORARY_PASSWORD_EXPIRED", error.code());
        verify(identities).logout(USER);
    }

    @Test
    void cambiarSoloSirveConTemporalVigente() {
        pending(null);
        assertThrows(IllegalArgumentException.class, () -> service.complete(USER, "NuevaClave-2026!"));
        pending(true);
        service.complete(USER, "NuevaClave-2026!");
        verify(identities).replacePassword(USER, "NuevaClave-2026!", false);
    }
}
