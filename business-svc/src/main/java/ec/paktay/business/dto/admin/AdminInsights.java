package ec.paktay.business.dto.admin;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** DTOs de Resumen, Notificaciones y Estado del sistema. Solo conteos: nunca montos. */
public final class AdminInsights {
    private AdminInsights() {
    }

    /** Cifras del mes calendario (zona America/Guayaquil). */
    public record Metrics(long activeUsers, long newUsersThisMonth, long walletCaptures, long activeCards,
                          long pushDelivered, long pushSent, List<PlanCount> plans, List<WeekCount> signupsByWeek) {
    }

    /** key: PRO_BETA, PRO_TESTER, PRO_STORE, FREE, DUO. */
    public record PlanCount(String key, String label, long count) {
    }

    /** week: "S38" (semana ISO); weekStart: lunes de esa semana. */
    public record WeekCount(String week, LocalDate weekStart, long count) {
    }

    public record NotificationSummary(long sentThisMonth, long delivered, long failed, long devicesWithPush, long devicesTotal) {
    }

    public record NotificationLog(UUID id, String kind, String email, int threshold, String periodMonth,
                                  boolean delivered, OffsetDateTime createdAt) {
    }

    /** sent: tokens a los que se envió; delivered: los que Firebase aceptó. */
    public record TestPushResult(boolean delivered, int sent, String message) {
    }

    /** health: UP, WARN, UNMONITORED o DOWN. */
    public record Service(String id, String name, String description, String health, String metric, String metricDetail) {
    }

    public record Job(String name, String schedule) {
    }

    /** level: danger o warning. */
    public record Warning(String level, String title, String detail) {
    }

    public record Version(String label, String value) {
    }

    public record SystemStatus(OffsetDateTime checkedAt, List<Service> services, List<Job> jobs,
                               List<Warning> warnings, List<Version> versions) {
    }
}
