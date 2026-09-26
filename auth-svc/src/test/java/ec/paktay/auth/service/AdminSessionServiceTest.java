package ec.paktay.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import ec.paktay.auth.config.KeycloakProperties;
import ec.paktay.auth.dto.TokenResponse;
import ec.paktay.auth.exception.AdminSessionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class AdminSessionServiceTest {
    private static final TokenResponse TOKENS = new TokenResponse("access", "refresh", 900, 1800, "Bearer");

    private KeycloakIdentityService identities;
    private JwtDecoder decoder;
    private AdminAuditWriter audit;
    private AdminSessionService sessions;

    @BeforeEach
    void setUp() {
        identities = mock(KeycloakIdentityService.class);
        decoder = mock(JwtDecoder.class);
        audit = mock(AdminAuditWriter.class);
        var properties = new KeycloakProperties("http://kc", "http://kc", "paktay", "paktay-mobile",
                "paktay-auth-service", "s", "paktay-admin-panel", "p");
        sessions = new AdminSessionService(identities, decoder, audit, properties);
    }

    @Test
    void adminDelPanelEntraYQuedaAuditado() {
        when(identities.adminPanelLogin("ana@paktay.app", "clave")).thenReturn(TOKENS);
        when(decoder.decode("access")).thenReturn(jwt("paktay-admin-panel", "ADMIN", "USER"));

        assertSame(TOKENS, sessions.login("  Ana@Paktay.app ", "clave"));
        verify(audit).adminAction(any(), eq("Inicio de sesión en el panel"), eq("u-1"), anyString(), any(), any());
        verify(identities, never()).adminPanelLogout(anyString());
    }

    @Test
    void sinRolAdminRecibeElMismoErrorQueUnaContraseniaMalaYSeCierraLaSesion() {
        when(identities.adminPanelLogin(anyString(), anyString())).thenReturn(TOKENS);
        when(decoder.decode("access")).thenReturn(jwt("paktay-admin-panel", "USER"));

        AdminSessionException error = assertThrows(AdminSessionException.class, () -> sessions.login("u@paktay.app", "clave"));
        assertEquals("INVALID_CREDENTIALS", error.code());
        verify(identities).adminPanelLogout("refresh");
        verify(audit, never()).adminAction(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void tokenDeOtroClienteNoSirve() {
        when(identities.adminPanelLogin(anyString(), anyString())).thenReturn(TOKENS);
        when(decoder.decode("access")).thenReturn(jwt("paktay-mobile", "ADMIN"));

        assertEquals("INVALID_CREDENTIALS", assertThrows(AdminSessionException.class,
                () -> sessions.login("u@paktay.app", "clave")).code());
    }

    @Test
    void renovarSinRolAdminTerminaLaSesion() {
        when(identities.adminPanelRefresh("refresh")).thenReturn(TOKENS);
        when(decoder.decode("access")).thenReturn(jwt("paktay-admin-panel", "USER"));

        assertEquals("SESSION_EXPIRED", assertThrows(AdminSessionException.class, () -> sessions.refresh("refresh")).code());
        verify(identities).adminPanelLogout("refresh");
    }

    private static Jwt jwt(String azp, String... roles) {
        return Jwt.withTokenValue("access").header("alg", "RS256").subject("u-1")
                .claim("azp", azp).claim("email", "ana@paktay.app")
                .claim("realm_access", Map.of("roles", List.of(roles))).build();
    }
}
