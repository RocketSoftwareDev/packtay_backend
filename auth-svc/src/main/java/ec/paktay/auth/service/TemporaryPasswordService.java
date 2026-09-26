package ec.paktay.auth.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ec.paktay.auth.dto.TokenResponse;
import ec.paktay.auth.exception.CodedException;
import ec.paktay.auth.exception.ConflictException;
import ec.paktay.auth.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;

/**
 * Contraseña temporal que envía el administrador (tabla password_temporary, V10).
 *
 * 1. El admin la pide desde el panel: se genera, se pone en Keycloak como contraseña normal, se
 *    cierran las sesiones de la cuenta, se quita el bloqueo por intentos y se manda por correo.
 *    El admin nunca la ve.
 * 2. La persona entra al móvil con ella: el login responde password_change_required = true y la
 *    app no deja seguir hasta elegir una nueva ({@link #complete}).
 * 3. Vence a las 24 horas: vencida, el login se rechaza y queda la recuperación por PIN.
 *
 * No se usa la marca "temporal" de Keycloak: con el login por usuario y contraseña (direct grant)
 * esa marca responde "Account is not fully set up" y la cuenta no puede entrar a ningún lado.
 */
@Service
public class TemporaryPasswordService {
    static final int VALID_HOURS = 24;
    private static final Logger log = LoggerFactory.getLogger(TemporaryPasswordService.class);
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!#%*+=?@";

    public enum Status { NONE, REQUIRED, EXPIRED }

    private final KeycloakIdentityService identities;
    private final PasswordMailService mail;
    private final AdminAuditWriter audit;
    private final JdbcTemplate db;
    private final JwtDecoder decoder;
    private final SecureRandom random = new SecureRandom();

    public TemporaryPasswordService(KeycloakIdentityService identities, PasswordMailService mail, AdminAuditWriter audit,
                                    JdbcTemplate db, JwtDecoder decoder) {
        this.identities = identities;
        this.mail = mail;
        this.audit = audit;
        this.db = db;
        this.decoder = decoder;
    }

    public void issue(String userId, AdminActor actor) {
        if (userId.equals(actor.id())) {
            throw new ConflictException("Para tu propia cuenta usa «Cambiar contraseña» en la app.");
        }
        Map<?, ?> user = identities.findById(userId);
        if (user == null) throw new NotFoundException("El usuario no existe");
        if (Boolean.FALSE.equals(user.get("enabled"))) {
            throw new ConflictException("La cuenta está bloqueada: desbloquéala antes de enviar una contraseña temporal.");
        }
        if (!(user.get("email") instanceof String email) || email.isBlank()) {
            throw new ConflictException("La cuenta no tiene un correo al que enviar la contraseña.");
        }

        String password = generate();
        identities.replacePassword(userId, password, false);
        identities.clearBruteForce(userId);
        identities.logout(userId);
        db.update("""
                insert into password_temporary (user_id, expires_at, created_by)
                values (cast(? as uuid), now() + make_interval(hours => ?), cast(? as uuid))
                on conflict (user_id) do update
                   set expires_at = excluded.expires_at, created_by = excluded.created_by, created_at = now()
                """, userId, VALID_HOURS, actor.id());
        try {
            mail.sendTemporaryPassword(email, password, VALID_HOURS);
        } catch (RuntimeException ex) {
            log.error("temporary_password_mail_failed subject={} reason={}", userId, ex.getMessage());
            throw new IllegalStateException("La contraseña se cambió pero no se pudo enviar el correo. Vuelve a intentarlo.");
        }
        audit.adminAction(actor, "Contraseña temporal enviada", userId, email, null, Map.of("expiresInHours", VALID_HOURS));
        log.info("temporary_password_issued subject={} by={}", userId, actor.id());
    }

    public Status status(String userId) {
        List<Boolean> rows = db.queryForList(
                "select expires_at > now() from password_temporary where user_id = cast(? as uuid)", Boolean.class, userId);
        if (rows.isEmpty()) return Status.NONE;
        return Boolean.TRUE.equals(rows.get(0)) ? Status.REQUIRED : Status.EXPIRED;
    }

    /**
     * Después del login del móvil: con contraseña temporal vigente marca los tokens; vencida, cierra
     * la sesión recién abierta y rechaza el login.
     */
    public TokenResponse afterMobileLogin(TokenResponse tokens) {
        String userId = decoder.decode(tokens.accessToken()).getSubject();
        return switch (status(userId)) {
            case NONE -> tokens;
            case REQUIRED -> tokens.withPasswordChangeRequired();
            case EXPIRED -> {
                identities.logout(userId);
                throw CodedException.temporaryPasswordExpired();
            }
        };
    }

    public void complete(String userId, String newPassword) {
        if (status(userId) != Status.REQUIRED) {
            throw new IllegalArgumentException("No tienes una contraseña temporal pendiente de cambio.");
        }
        identities.replacePassword(userId, newPassword, false);
        db.update("delete from password_temporary where user_id = cast(? as uuid)", userId);
        log.info("temporary_password_replaced subject={}", userId);
    }

    /** 16 caracteres con las cuatro clases que pide la política del realm, sin caracteres confusos. */
    String generate() {
        List<Character> chars = new ArrayList<>();
        for (String pool : List.of(UPPER, LOWER, DIGITS, SYMBOLS)) chars.add(pick(pool));
        String all = UPPER + LOWER + DIGITS + SYMBOLS;
        while (chars.size() < 16) chars.add(pick(all));
        Collections.shuffle(chars, random);
        StringBuilder result = new StringBuilder();
        chars.forEach(result::append);
        return result.toString();
    }

    private char pick(String pool) {
        return pool.charAt(random.nextInt(pool.length()));
    }
}
