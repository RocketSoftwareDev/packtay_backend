package ec.paktay.business.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.admin.AdminInsights;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class PushDeliveryLogService {
    private final JdbcClient jdbc;

    public PushDeliveryLogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID userId, UUID deviceId, String kind, PushSender.Outcome outcome) {
        jdbc.sql("""
                insert into push_delivery_log (user_id, device_id, kind, result)
                values (:userId, :deviceId, :kind, :result)
                """).param("userId", userId).param("deviceId", deviceId).param("kind", kind)
                .param("result", outcome.name()).update();
    }

    public AdminInsights.NotificationSummary summary() {
        long sent = count("select count(*) from push_delivery_log where created_at >= " + monthStart());
        long delivered = count("select count(*) from push_delivery_log where result = 'SENT' and created_at >= " + monthStart());
        long devices = count("select count(*) from user_devices where push_token is not null");
        long total = count("select count(*) from user_devices");
        return new AdminInsights.NotificationSummary(sent, delivered, sent - delivered, devices, total);
    }

    public List<AdminInsights.NotificationLog> list(String result) {
        String filter = "DELIVERED".equals(result) ? " where p.result = 'SENT'" : "FAILED".equals(result)
                ? " where p.result <> 'SENT'" : "";
        return jdbc.sql("""
                select p.id, p.kind, coalesce(u.email, '—') as email, d.device_name, p.result, p.created_at
                  from push_delivery_log p
                  left join app_users u on u.id = p.user_id
                  left join user_devices d on d.id = p.device_id
                """ + filter + " order by p.created_at desc limit 200")
                .query((rs, row) -> new AdminInsights.NotificationLog(rs.getObject("id", UUID.class),
                        rs.getString("kind"), rs.getString("email"), rs.getString("device_name"),
                        rs.getString("result"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String monthStart() {
        return "(date_trunc('month', now() at time zone 'America/Guayaquil') at time zone 'America/Guayaquil')";
    }
}
