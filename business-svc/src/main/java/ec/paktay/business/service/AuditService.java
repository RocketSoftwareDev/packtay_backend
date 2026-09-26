package ec.paktay.business.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purga de la bitácora vieja (audit_log). Desde V9 ya no recibe filas: las acciones del
 * usuario sobre gastos y tarjetas dejaron de auditarse (el panel no ve datos financieros)
 * y la bitácora del panel es admin_audit ({@link AdminAuditService}). Esta purga la
 * vacía en 90 días; después se puede borrar la tabla en una migración.
 */
@Service
public class AuditService {
    /** Días que se conserva la bitácora. El trigger audit_log_no_delete (V4) usa el mismo valor. */
    public static final int RETENTION_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
