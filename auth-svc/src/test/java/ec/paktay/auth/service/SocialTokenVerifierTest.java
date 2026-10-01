package ec.paktay.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import ec.paktay.auth.dto.SocialLoginRequest;
import ec.paktay.auth.exception.CodedException;
import ec.paktay.auth.exception.NotFoundException;
import ec.paktay.auth.service.SocialTokenVerifier.SocialIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SocialTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final List<String> GOOGLE_AUD = List.of("ios-client.apps.googleusercontent.com");
    private static final List<String> APPLE_AUD = List.of("com.rocket.paktay");
    private static final String RAW_NONCE = "nonce-sin-cifrar-123";

    private static Jwt token(String issuer, String aud, Consumer<Jwt.Builder> extra) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(issuer).audience(List.of(aud)).subject("proveedor-sub-1")
                .issuedAt(NOW.minusSeconds(60)).expiresAt(NOW.plusSeconds(600))
                .claim("email", "Ana@Ejemplo.com").claim("email_verified", true);
        extra.accept(builder);
        return builder.build();
    }

    private static Jwt google(Consumer<Jwt.Builder> extra) {
        return token("https://accounts.google.com", GOOGLE_AUD.get(0), extra);
    }

    private static Jwt apple(Consumer<Jwt.Builder> extra) {
        return token("https://appleid.apple.com", APPLE_AUD.get(0),
                b -> { b.claim("nonce", SocialTokenVerifier.sha256Hex(RAW_NONCE)).claim("email_verified", "true"); extra.accept(b); });
    }

    private static String code(Runnable call) {
        return assertThrows(CodedException.class, call::run).code();
    }

    @Test
    void googleValidoDevuelveLaIdentidad() {
        SocialIdentity identity = SocialTokenVerifier.validate(SocialProvider.GOOGLE,
                google(b -> b.claim("given_name", "Ana").claim("family_name", "Pérez")), GOOGLE_AUD, null, NOW);
        assertEquals("proveedor-sub-1", identity.subject());
        assertEquals("ana@ejemplo.com", identity.email());
        assertEquals("Ana", identity.givenName());
    }

    @Test
    void googleAceptaElEmisorSinEsquema() {
        Jwt jwt = token("accounts.google.com", GOOGLE_AUD.get(0), b -> { });
        assertEquals("ana@ejemplo.com", SocialTokenVerifier.validate(SocialProvider.GOOGLE, jwt, GOOGLE_AUD, null, NOW).email());
    }

    @Test
    void appleValidoConElNonceCifrado() {
        SocialIdentity identity = SocialTokenVerifier.validate(SocialProvider.APPLE, apple(b -> { }), APPLE_AUD, RAW_NONCE, NOW);
        assertEquals("ana@ejemplo.com", identity.email());
        assertNull(identity.givenName());
    }

    @Test
    void appleSinNonceOConOtroNonceSeRechaza() {
        assertEquals("SOCIAL_TOKEN_INVALID", code(() -> SocialTokenVerifier.validate(SocialProvider.APPLE, apple(b -> { }), APPLE_AUD, null, NOW)));
        assertEquals("SOCIAL_TOKEN_INVALID", code(() -> SocialTokenVerifier.validate(SocialProvider.APPLE, apple(b -> { }), APPLE_AUD, "otro", NOW)));
    }

    @Test
    void otraAudienciaOtroEmisorOVencidoSeRechaza() {
        assertEquals("SOCIAL_TOKEN_INVALID", code(() -> SocialTokenVerifier.validate(SocialProvider.GOOGLE,
                token("https://accounts.google.com", "otra-app", b -> { }), GOOGLE_AUD, null, NOW)));
        assertEquals("SOCIAL_TOKEN_INVALID", code(() -> SocialTokenVerifier.validate(SocialProvider.GOOGLE,
                token("https://appleid.apple.com", GOOGLE_AUD.get(0), b -> { }), GOOGLE_AUD, null, NOW)));
        assertEquals("SOCIAL_TOKEN_INVALID", code(() -> SocialTokenVerifier.validate(SocialProvider.GOOGLE,
                google(b -> b.expiresAt(NOW.minusSeconds(1))), GOOGLE_AUD, null, NOW)));
    }

    @Test
    void sinCorreoOSinVerificarTieneSuPropioCodigo() {
        assertEquals("SOCIAL_EMAIL_MISSING", code(() -> SocialTokenVerifier.validate(SocialProvider.GOOGLE,
                google(b -> b.claims(c -> c.remove("email"))), GOOGLE_AUD, null, NOW)));
        assertEquals("SOCIAL_EMAIL_UNVERIFIED", code(() -> SocialTokenVerifier.validate(SocialProvider.GOOGLE,
                google(b -> b.claim("email_verified", false)), GOOGLE_AUD, null, NOW)));
    }

    @Test
    void proveedorDesconocidoEs404() {
        assertThrows(NotFoundException.class, () -> SocialProvider.fromPath("facebook"));
        assertEquals(SocialProvider.APPLE, SocialProvider.fromPath("apple"));
    }

    @Test
    void nombreVisibleSinInventar() {
        SocialIdentity sinNombre = new SocialIdentity(SocialProvider.APPLE, "s", "x@privaterelay.appleid.com", null, null);
        assertNull(SocialLoginService.displayName(sinNombre, new SocialLoginRequest("t", "n", null, null, null)));
        assertEquals("Ana Pérez", SocialLoginService.displayName(sinNombre, new SocialLoginRequest("t", "n", null, " Ana ", "Pérez")));
        SocialIdentity google = new SocialIdentity(SocialProvider.GOOGLE, "s", "a@b.com", "Luis", null);
        assertEquals("Luis", SocialLoginService.displayName(google, new SocialLoginRequest("t", null, null, null, null)));
    }

    @Test
    void elNonceDeAppleEsElSha256EnHex() {
        assertEquals(64, SocialTokenVerifier.sha256Hex("x").length());
        assertTrue(SocialTokenVerifier.nonceMatches(SocialTokenVerifier.sha256Hex("abc"), "abc"));
    }
}
