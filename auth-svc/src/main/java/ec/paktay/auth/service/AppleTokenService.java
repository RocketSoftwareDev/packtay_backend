package ec.paktay.auth.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import ec.paktay.auth.config.SocialProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sign in with Apple, del lado del servidor (día 8c).
 *
 * Apple exige que, al eliminar la cuenta, la app revoque el acceso que el usuario le dio
 * (App Store Review 5.1.1(v)). Para eso hace falta su refresh token, que solo se obtiene
 * canjeando el código de autorización del primer inicio de sesión. Se guarda en
 * social_provider_tokens (V13) y se revoca antes de borrar los datos.
 *
 * El client_secret de Apple no es un texto fijo: es un JWT ES256 firmado con la key .p8, que
 * vale como mucho 6 meses. Se firma uno de 30 días y se reutiliza mientras le queden más de 5.
 *
 * Nada de esto bloquea: si Apple no responde o falta la key, el login sigue y queda un aviso.
 */
@Service
public class AppleTokenService {
    private static final Logger log = LoggerFactory.getLogger(AppleTokenService.class);
    private static final Duration SECRET_VALIDITY = Duration.ofDays(30);
    private static final Duration SECRET_RENEW_BEFORE = Duration.ofDays(5);

    private final SocialProperties properties;
    private final RestClient apple;
    private final JdbcTemplate db;
    private String cachedSecret;
    private Instant cachedSecretExpiresAt = Instant.EPOCH;

    public AppleTokenService(SocialProperties properties, @Qualifier("appleRestClient") RestClient apple, JdbcTemplate db) {
        this.properties = properties;
        this.apple = apple;
        this.db = db;
    }

    /** Canjea el código del primer inicio de sesión y guarda el refresh token para revocarlo después. */
    public void storeRefreshToken(String userId, String authorizationCode) {
        if (authorizationCode == null || authorizationCode.isBlank()) return;
        if (!properties.appleRevocationConfigured()) {
            log.warn("apple_revocation_not_configured userId={}", userId);
            return;
        }
        try {
            Map<?, ?> response = apple.post().uri("/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("client_id=" + encode(clientId()) + "&client_secret=" + encode(clientSecret())
                            + "&code=" + encode(authorizationCode) + "&grant_type=authorization_code")
                    .retrieve().body(Map.class);
            Object refresh = response == null ? null : response.get("refresh_token");
            if (refresh == null) {
                log.warn("apple_token_without_refresh userId={}", userId);
                return;
            }
            db.update("""
                    insert into social_provider_tokens (user_id, provider, refresh_token) values (cast(? as uuid), 'APPLE', ?)
                    on conflict (user_id, provider) do update set refresh_token = excluded.refresh_token, updated_at = now()
                    """, userId, String.valueOf(refresh));
        } catch (RestClientException | IllegalStateException ex) {
            log.warn("apple_token_redeem_failed userId={} reason={}", userId, ex.getClass().getSimpleName());
        }
    }

    /** Revoca en Apple antes de borrar la cuenta. Sin token guardado no hay nada que hacer. */
    public void revokeFor(String userId) {
        List<String> tokens = db.queryForList(
                "select refresh_token from social_provider_tokens where user_id = cast(? as uuid) and provider = 'APPLE'",
                String.class, userId);
        if (tokens.isEmpty()) return;
        if (!properties.appleRevocationConfigured()) {
            log.warn("apple_revocation_not_configured userId={}", userId);
            return;
        }
        try {
            apple.post().uri("/auth/revoke")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("client_id=" + encode(clientId()) + "&client_secret=" + encode(clientSecret())
                            + "&token=" + encode(tokens.get(0)) + "&token_type_hint=refresh_token")
                    .retrieve().toBodilessEntity();
            log.info("apple_access_revoked userId={}", userId);
        } catch (RestClientException | IllegalStateException ex) {
            // No frena la eliminación: el usuario pidió borrar y sus datos se borran igual.
            log.warn("apple_revoke_failed userId={} reason={}", userId, ex.getClass().getSimpleName());
        }
    }

    private String clientId() {
        return properties.appleAudiences().get(0);
    }

    synchronized String clientSecret() {
        Instant now = Instant.now();
        if (cachedSecret != null && cachedSecretExpiresAt.minus(SECRET_RENEW_BEFORE).isAfter(now)) return cachedSecret;
        try {
            String pem = Files.readString(Path.of(properties.applePrivateKeyPath()));
            String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            ECPrivateKey key = (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            Instant expires = now.plus(SECRET_VALIDITY);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(properties.appleKeyId()).build(),
                    new JWTClaimsSet.Builder()
                            .issuer(properties.appleTeamId())
                            .issueTime(Date.from(now))
                            .expirationTime(Date.from(expires))
                            .audience("https://appleid.apple.com")
                            .subject(clientId())
                            .build());
            jwt.sign(new ECDSASigner(key));
            cachedSecret = jwt.serialize();
            cachedSecretExpiresAt = expires;
            return cachedSecret;
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible firmar el secreto de Apple", ex);
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
