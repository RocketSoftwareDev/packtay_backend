package ec.paktay.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ec.paktay.auth.config.SocialProperties;
import ec.paktay.auth.exception.CodedException;
import ec.paktay.auth.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

/**
 * Valida el ID token que la app recibe de Apple o Google, antes de tocar Keycloak:
 *
 * - firma con las llaves públicas del proveedor y vencimiento (Nimbus);
 * - emisor del proveedor y audiencia = nuestro Client ID (Google) o bundle id (Apple);
 * - Apple: el nonce del token es el SHA-256 del nonce que la app generó (evita reusar un token);
 * - correo presente y verificado: con él se vincula a una cuenta que ya existía.
 *
 * Keycloak vuelve a validar firma y emisor en el token exchange; esto es lo que él no revisa
 * (audiencia, nonce, correo) y deja un error claro antes de crear o vincular nada.
 */
@Service
public class SocialTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(SocialTokenVerifier.class);

    private final SocialProperties properties;
    private final Map<SocialProvider, JwtDecoder> decoders = new EnumMap<>(SocialProvider.class);

    public SocialTokenVerifier(SocialProperties properties) {
        this.properties = properties;
    }

    public record SocialIdentity(SocialProvider provider, String subject, String email, String givenName, String familyName) { }

    public SocialIdentity verify(SocialProvider provider, String idToken, String rawNonce) {
        List<String> audiences = audiences(provider);
        if (audiences.isEmpty()) throw new NotFoundException("Proveedor no disponible");
        Jwt jwt;
        try {
            jwt = decoder(provider).decode(idToken);
        } catch (JwtException ex) {
            log.warn("social_token_rejected provider={} reason={}", provider, ex.getClass().getSimpleName());
            throw invalid(provider);
        }
        return validate(provider, jwt, audiences, rawNonce, Instant.now());
    }

    /** Las comprobaciones de los claims, sin red: separadas para poder probarlas. */
    static SocialIdentity validate(SocialProvider provider, Jwt jwt, List<String> audiences, String rawNonce, Instant now) {
        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null || !provider.issuers().contains(issuer)) throw invalid(provider);
        List<String> aud = jwt.getAudience();
        if (aud == null || aud.stream().noneMatch(audiences::contains)) throw invalid(provider);
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) throw invalid(provider);
        if (provider == SocialProvider.APPLE && !nonceMatches(jwt.getClaimAsString("nonce"), rawNonce)) throw invalid(provider);
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) throw invalid(provider);

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new CodedException(HttpStatus.BAD_REQUEST, "SOCIAL_EMAIL_MISSING",
                    "Tu cuenta de " + provider.label() + " no compartió un correo. Entra con tu correo y contraseña.");
        }
        Object verified = jwt.getClaims().get("email_verified");
        if (!(Boolean.TRUE.equals(verified) || "true".equals(verified))) {
            throw new CodedException(HttpStatus.BAD_REQUEST, "SOCIAL_EMAIL_UNVERIFIED",
                    "Tu correo de " + provider.label() + " no está verificado. Verifícalo allí y vuelve a intentarlo.");
        }
        return new SocialIdentity(provider, subject, email.trim().toLowerCase(Locale.ROOT),
                jwt.getClaimAsString("given_name"), jwt.getClaimAsString("family_name"));
    }

    /** Apple guarda el SHA-256 (hex) del nonce que la app le pasó; se acepta también el valor tal cual. */
    static boolean nonceMatches(String tokenNonce, String rawNonce) {
        if (tokenNonce == null || rawNonce == null || rawNonce.isBlank()) return false;
        return tokenNonce.equals(sha256Hex(rawNonce)) || tokenNonce.equals(rawNonce);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<String> audiences(SocialProvider provider) {
        return provider == SocialProvider.APPLE ? properties.appleAudiences() : properties.googleAudiences();
    }

    private synchronized JwtDecoder decoder(SocialProvider provider) {
        return decoders.computeIfAbsent(provider, p -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(p.jwksUrl()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator()));
            return decoder;
        });
    }

    private static CodedException invalid(SocialProvider provider) {
        return new CodedException(HttpStatus.BAD_REQUEST, "SOCIAL_TOKEN_INVALID",
                "No pudimos verificar tu cuenta de " + provider.label() + ". Inténtalo otra vez.");
    }
}
