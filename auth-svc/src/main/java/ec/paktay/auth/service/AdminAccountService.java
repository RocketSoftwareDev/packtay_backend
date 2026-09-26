package ec.paktay.auth.service;

import java.util.List;
import java.util.Map;

import ec.paktay.auth.dto.AdminSummary;
import ec.paktay.auth.exception.ConflictException;
import ec.paktay.auth.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Acciones del panel sobre una cuenta que tocan Keycloak. Todas quedan en admin_audit.
 *
 * Bloquear: desactiva la identidad (no puede entrar ni renovar tokens), cierra sus
 * sesiones y marca app_users INACTIVE (business-svc responde 403 ACCOUNT_BLOCKED a
 * cualquier token que aún no venza). No borra nada y es reversible.
 *
 * Rol ADMIN: nadie se lo quita a sí mismo y no se puede quitar el último administrador,
 * para no dejar el panel sin nadie que pueda entrar.
 */
@Service
public class AdminAccountService {
    public static final String ADMIN_ROLE = "ADMIN";
    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    private final KeycloakIdentityService identities;
    private final JdbcTemplate db;
    private final AdminAuditWriter audit;

    public AdminAccountService(KeycloakIdentityService identities, JdbcTemplate db, AdminAuditWriter audit) {
        this.identities = identities;
        this.db = db;
        this.audit = audit;
    }

    public void block(String userId, AdminActor actor) {
        if (userId.equals(actor.id())) throw new ConflictException("No puedes bloquear tu propia cuenta.");
        Map<?, ?> user = require(userId);
        identities.setEnabled(userId, false);
        identities.logout(userId);
        db.update("update app_users set status = 'INACTIVE', deactivated_at = coalesce(deactivated_at, now()) where id = cast(? as uuid)", userId);
        audit.adminAction(actor, "Usuario bloqueado", userId, emailOf(user), Map.of("status", "ACTIVE"), Map.of("status", "BLOCKED"));
        log.info("account_blocked subject={} by={}", userId, actor.id());
    }

    public void unblock(String userId, AdminActor actor) {
        Map<?, ?> user = require(userId);
        identities.setEnabled(userId, true);
        db.update("update app_users set status = 'ACTIVE', deactivated_at = null where id = cast(? as uuid)", userId);
        audit.adminAction(actor, "Usuario desbloqueado", userId, emailOf(user), Map.of("status", "BLOCKED"), Map.of("status", "ACTIVE"));
        log.info("account_unblocked subject={} by={}", userId, actor.id());
    }

    public void replacePassword(String userId, String password, boolean temporary, AdminActor actor) {
        Map<?, ?> user = require(userId);
        identities.replacePassword(userId, password, temporary);
        audit.adminAction(actor, "Contraseña restablecida", userId, emailOf(user) + (temporary ? " · temporal" : ""),
                null, Map.of("temporary", temporary));
    }

    public List<AdminSummary> admins() {
        return identities.usersWithRealmRole(ADMIN_ROLE).stream()
                .map(user -> new AdminSummary(String.valueOf(user.get("id")), stringOrNull(user.get("email")),
                        displayName(user), !Boolean.FALSE.equals(user.get("enabled"))))
                .toList();
    }

    public void grantAdmin(String userId, AdminActor actor) {
        Map<?, ?> user = require(userId);
        if (isAdmin(userId)) return;
        identities.grantRealmRole(userId, ADMIN_ROLE);
        audit.adminAction(actor, "Rol ADMIN asignado", userId, emailOf(user), Map.of("admin", false), Map.of("admin", true));
        log.info("admin_role_granted subject={} by={}", userId, actor.id());
    }

    public void revokeAdmin(String userId, AdminActor actor) {
        if (userId.equals(actor.id())) throw new ConflictException("No puedes quitarte el rol de administrador a ti mismo.");
        Map<?, ?> user = require(userId);
        List<AdminSummary> admins = admins();
        if (admins.stream().noneMatch(admin -> admin.id().equals(userId))) return;
        long activeOthers = admins.stream().filter(admin -> admin.enabled() && !admin.id().equals(userId)).count();
        if (activeOthers == 0) throw new ConflictException("No se puede quitar el último administrador activo.");
        identities.revokeRealmRole(userId, ADMIN_ROLE);
        audit.adminAction(actor, "Rol ADMIN quitado", userId, emailOf(user), Map.of("admin", true), Map.of("admin", false));
        log.info("admin_role_revoked subject={} by={}", userId, actor.id());
    }

    private boolean isAdmin(String userId) {
        return admins().stream().anyMatch(admin -> admin.id().equals(userId));
    }

    private Map<?, ?> require(String userId) {
        Map<?, ?> user = identities.findById(userId);
        if (user == null) throw new NotFoundException("El usuario no existe");
        return user;
    }

    private static String emailOf(Map<?, ?> user) {
        Object email = user.get("email");
        return email == null ? String.valueOf(user.get("id")) : String.valueOf(email);
    }

    private static String displayName(Map<?, ?> user) {
        String first = stringOrNull(user.get("firstName"));
        String last = stringOrNull(user.get("lastName"));
        if (first == null) return stringOrNull(user.get("email"));
        return last == null || last.equals(first) ? first : first + " " + last;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
