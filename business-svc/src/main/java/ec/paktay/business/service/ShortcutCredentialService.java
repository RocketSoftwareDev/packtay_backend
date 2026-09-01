package ec.paktay.business.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import ec.paktay.business.dto.ShortcutCredentialResponse;
import ec.paktay.business.exception.ShortcutAuthenticationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShortcutCredentialService {
    private static final String PREFIX = "paktay_sc_";
    private final JdbcClient jdbc;
    private final UserAccountService users;
    private final SecureRandom random = new SecureRandom();

    public ShortcutCredentialService(JdbcClient jdbc, UserAccountService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional
    public ShortcutCredentialResponse rotate(UUID userId) {
        users.ensureActiveUser(userId);
        jdbc.sql("update shortcut_credentials set active = false, revoked_at = now() where user_id = :userId and active")
                .param("userId", userId).update();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String hint = token.substring(token.length() - 8);
        return jdbc.sql("""
                insert into shortcut_credentials (user_id, token_hash, token_hint)
                values (:userId, :hash, :hint)
                returning token_hint, created_at
                """).param("userId", userId).param("hash", hash(token)).param("hint", hint)
                .query((rs, rowNum) -> new ShortcutCredentialResponse(true, token, rs.getString("token_hint"),
                        rs.getObject("created_at", OffsetDateTime.class), null)).single();
    }

    public ShortcutCredentialResponse status(UUID userId) {
        users.ensureActiveUser(userId);
        return jdbc.sql("""
                select token_hint, created_at, last_used_at from shortcut_credentials
                 where user_id = :userId and active order by created_at desc limit 1
                """).param("userId", userId)
                .query((rs, rowNum) -> new ShortcutCredentialResponse(true, null, rs.getString("token_hint"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_used_at", OffsetDateTime.class)))
                .optional().orElse(new ShortcutCredentialResponse(false, null, null, null, null));
    }

    @Transactional
    public void revoke(UUID userId) {
        jdbc.sql("update shortcut_credentials set active = false, revoked_at = now() where user_id = :userId and active")
                .param("userId", userId).update();
    }

    @Transactional
    public UUID authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ShortcutAuthenticationException("Falta el código de conexión de PAKTAY");
        }
        String token = authorization.substring(7).trim();
        if (!token.startsWith(PREFIX)) {
            throw new ShortcutAuthenticationException("Código de conexión inválido");
        }
        UUID userId = jdbc.sql("""
                update shortcut_credentials set last_used_at = now()
                 where token_hash = :hash and active
                returning user_id
                """).param("hash", hash(token)).query(UUID.class).optional().orElseThrow(
                        () -> new ShortcutAuthenticationException("Código de conexión inválido o revocado"));
        return userId;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible", impossible);
        }
    }
}
