package ec.paktay.auth.service;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Escribe en admin_audit (V9, base de negocio) los eventos de identidad: altas, acciones
 * del administrador y cuentas eliminadas. business-svc la lee para el panel.
 *
 * Nunca rompe la operación principal: si la bitácora falla, se registra en el log.
 */
@Service
public class AdminAuditWriter {
    private static final Logger log = LoggerFactory.getLogger(AdminAuditWriter.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public AdminAuditWriter(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public void userCreated(String userId, String email, Map<String, ?> after) {
        write("USER_CREATED", "Alta de usuario", null, "registro en la app", userId, email, null, after);
    }

    /** Sin correo ni sujeto: purge_user ya borró todo lo del usuario. */
    public void accountDeleted(String userId, AdminActor actor) {
        write("ACCOUNT_DELETED", "Cuenta eliminada", actor == null ? null : actor.id(),
                actor == null ? "el propio usuario" : actor.label(), null,
                EmailRules.anonymousId(userId) + " · datos purgados", null, null);
    }

    public void adminAction(AdminActor actor, String action, String subjectUserId, String subjectLabel,
                            Map<String, ?> before, Map<String, ?> after) {
        write("ADMIN_ACTION", action, actor.id(), actor.label(), subjectUserId, subjectLabel, before, after);
    }

    private void write(String kind, String action, String actorId, String actorLabel, String subjectId,
                       String subjectLabel, Map<String, ?> before, Map<String, ?> after) {
        try {
            db.update("""
                    insert into admin_audit (kind, action, actor_id, actor_label, subject_user_id, subject_label, before_value, after_value)
                    values (?, ?, cast(? as uuid), ?, cast(? as uuid), ?, cast(? as jsonb), cast(? as jsonb))
                    """, kind, action, actorId, actorLabel, subjectId, subjectLabel == null ? "—" : subjectLabel,
                    toJson(before), toJson(after));
        } catch (RuntimeException ex) {
            log.error("admin_audit_write_failed kind={} action={} reason={}", kind, action, ex.getMessage());
        }
    }

    private String toJson(Map<String, ?> data) {
        if (data == null || data.isEmpty()) return null;
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
