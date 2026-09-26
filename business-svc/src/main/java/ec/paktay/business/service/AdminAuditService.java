package ec.paktay.business.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.admin.AdminAuditEvent;
import ec.paktay.business.dto.admin.AdminAuditPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bitácora del panel (admin_audit, V9). Solo tres tipos de evento: alta de usuario,
 * acción de un administrador y cuenta eliminada. before/after nunca llevan montos ni
 * datos financieros. auth-svc escribe los eventos de identidad en la misma tabla.
 */
@Service
public class AdminAuditService {
    public static final int RETENTION_DAYS = 90;
    public static final Set<String> KINDS = Set.of("USER_CREATED", "ADMIN_ACTION", "ACCOUNT_DELETED");
    private static final int MAX_ITEMS = 200;
    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AdminAuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Acción de un administrador, dentro de la transacción de quien llama. */
    @Transactional
    public void adminAction(AdminActor actor, String action, UUID subjectUserId, String subjectLabel,
                            Map<String, ?> before, Map<String, ?> after) {
        jdbc.sql("""
                insert into admin_audit (kind, action, actor_id, actor_label, subject_user_id, subject_label, before_value, after_value)
                values ('ADMIN_ACTION', :action, :actorId, :actorLabel, :subjectId, :subjectLabel, cast(:before as jsonb), cast(:after as jsonb))
                """).param("action", action).param("actorId", actor.id()).param("actorLabel", actor.label())
                .param("subjectId", subjectUserId, java.sql.Types.OTHER).param("subjectLabel", subjectLabel)
                .param("before", toJson(before), java.sql.Types.VARCHAR)
                .param("after", toJson(after), java.sql.Types.VARCHAR).update();
    }

    /**
     * Eventos de los últimos {@code days} días (máximo {@link #RETENTION_DAYS}), filtrados por
     * tipo y por texto en el sujeto o el actor, más el conteo por tipo del mismo período.
     */
    public AdminAuditPage list(String kind, String query, int days) {
        int window = Math.max(1, Math.min(days, RETENTION_DAYS));
        String pattern = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
        String kindFilter = kind == null || !KINDS.contains(kind) ? null : kind;

        List<AdminAuditEvent> items = jdbc.sql("""
                select id, kind, action, actor_label, subject_label, created_at, before_value::text as before_value, after_value::text as after_value
                  from admin_audit
                 where created_at >= now() - make_interval(days => :days)
                   and (cast(:kind as text) is null or kind = cast(:kind as text))
                   and (cast(:pattern as text) is null or subject_label ilike cast(:pattern as text) or actor_label ilike cast(:pattern as text))
                 order by created_at desc
                 limit :limit
                """).param("days", window).param("kind", kindFilter, java.sql.Types.VARCHAR)
                .param("pattern", pattern, java.sql.Types.VARCHAR).param("limit", MAX_ITEMS)
                .query(this::map).list();

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", 0L);
        KINDS.forEach(item -> counts.put(item, 0L));
        jdbc.sql("""
                select kind, count(*) as total
                  from admin_audit
                 where created_at >= now() - make_interval(days => :days)
                   and (cast(:pattern as text) is null or subject_label ilike cast(:pattern as text) or actor_label ilike cast(:pattern as text))
                 group by kind
                """).param("days", window).param("pattern", pattern, java.sql.Types.VARCHAR)
                .query((rs, row) -> Map.entry(rs.getString("kind"), rs.getLong("total"))).list()
                .forEach(entry -> {
                    counts.put(entry.getKey(), entry.getValue());
                    counts.merge("ALL", entry.getValue(), Long::sum);
                });
        return new AdminAuditPage(items, counts);
    }

    @Scheduled(cron = "0 35 3 * * *")
    @Transactional
    public void purgeExpired() {
        int deleted = jdbc.sql("delete from admin_audit where created_at < now() - make_interval(days => :days)")
                .param("days", RETENTION_DAYS).update();
        log.info("admin_audit_purged rows={} retentionDays={}", deleted, RETENTION_DAYS);
    }

    private AdminAuditEvent map(ResultSet rs, int row) throws SQLException {
        return new AdminAuditEvent(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("action"),
                rs.getString("actor_label"), rs.getString("subject_label"), rs.getObject("created_at", OffsetDateTime.class),
                parse(rs.getString("before_value")), parse(rs.getString("after_value")));
    }

    private String toJson(Map<String, ?> data) {
        if (data == null || data.isEmpty()) return null;
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar la entrada de auditoría", ex);
        }
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
