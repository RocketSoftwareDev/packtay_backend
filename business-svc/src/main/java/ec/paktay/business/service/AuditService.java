package ec.paktay.business.service;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escribe en audit_log las acciones relevantes del usuario y purga lo que pasó
 * de 90 días.
 *
 * {@code data} va a {@code after_value} y nunca debe llevar datos personales
 * (correo, nombre, nombre de Wallet): sólo identificadores y banderas.
 * La acción debe existir en el enum audit_action (V1 + V4 + V5: VOID).
 */
@Service
public class AuditService {
    /** Días que se conserva la bitácora. El trigger audit_log_no_delete (V4) usa el mismo valor. */
    public static final int RETENTION_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Registra una acción hecha por el propio usuario sobre sus datos, dentro de la
     * transacción de quien llama: si la operación se revierte, la entrada también.
     */
    @Transactional
    public void record(UUID userId, String action, String entity, UUID entityId, Map<String, ?> data) {
        String payload;
        try {
            payload = data == null || data.isEmpty() ? null : json.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar la entrada de auditoría", ex);
        }
        jdbc.sql("""
                insert into audit_log (actor_user_id, subject_user_id, entity_type, entity_id, action, after_value)
                values (:userId, :userId, :entity, :entityId, cast(:action as audit_action), cast(:data as jsonb))
                """).param("userId", userId).param("entity", entity)
                .param("entityId", entityId, java.sql.Types.OTHER)
                .param("action", action).param("data", payload, java.sql.Types.VARCHAR).update();
    }

    /** Purga diaria de entradas con más de {@link #RETENTION_DAYS} días. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        int deleted = jdbc.sql("delete from audit_log where created_at < now() - make_interval(days => :days)")
                .param("days", RETENTION_DAYS).update();
        log.info("audit_log_purged rows={} retentionDays={}", deleted, RETENTION_DAYS);
    }
}
