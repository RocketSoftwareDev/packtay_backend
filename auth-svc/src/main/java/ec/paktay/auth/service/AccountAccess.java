package ec.paktay.auth.service;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * ¿La cuenta está bloqueada por un administrador, o solo pausada por intentos fallidos?
 *
 * Keycloak responde enabled = false en los dos casos: al consultar una cuenta por id, la pausa
 * de fuerza bruta también sale como desactivada. Para el PIN y la contraseña temporal no son lo
 * mismo: la pausa se resuelve recuperando la contraseña (y el PIN la quita); el bloqueo del admin
 * solo lo resuelve soporte. El bloqueo del admin marca app_users INACTIVE (AdminAccountService).
 */
@Service
public class AccountAccess {
    private final JdbcTemplate db;
    private final KeycloakIdentityService identities;

    public AccountAccess(JdbcTemplate db, KeycloakIdentityService identities) {
        this.db = db;
        this.identities = identities;
    }

    public boolean blockedByAdmin(Map<?, ?> user) {
        if (user == null || !Boolean.FALSE.equals(user.get("enabled"))) return false;
        if (!(user.get("id") instanceof String id)) return true;
        List<String> status = db.queryForList("select status from app_users where id = cast(? as uuid)", String.class, id);
        if (!status.isEmpty() && "INACTIVE".equals(status.get(0))) return true;
        // Desactivada sin marca del panel: si es la pausa por intentos, no está bloqueada.
        return !identities.isTemporarilyLocked(id);
    }

    /** Existe y puede recuperar o cambiar su contraseña (aunque esté pausada por intentos). */
    public boolean usable(Map<?, ?> user) {
        return user != null && !blockedByAdmin(user);
    }
}
