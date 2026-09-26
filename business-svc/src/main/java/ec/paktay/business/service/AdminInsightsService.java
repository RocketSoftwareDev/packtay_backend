package ec.paktay.business.service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ec.paktay.business.config.KeycloakHealthIndicator;
import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.admin.AdminInsights;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Resumen, Notificaciones y Estado del sistema del panel. Todo son conteos agregados:
 * ningún monto ni dato financiero individual.
 */
@Service
public class AdminInsightsService {
    /** Zona de los cortes de mes y semana del panel (la de la mayoría de usuarios). */
    static final ZoneId PANEL_ZONE = ZoneId.of(UserAccountService.DEFAULT_TIMEZONE);
    private static final String MONTH_START = "(date_trunc('month', now() at time zone 'America/Guayaquil') at time zone 'America/Guayaquil')";

    private final JdbcClient jdbc;
    private final DeviceService devices;
    private final PushSender push;
    private final KeycloakHealthIndicator keycloak;
    private final RestClient authHealth;
    private final String corsOrigins;

    public AdminInsightsService(JdbcClient jdbc, DeviceService devices, PushSender push, KeycloakHealthIndicator keycloak,
                                @Value("${paktay.admin.auth-health-url:http://localhost:8081/actuator/health}") String authHealthUrl,
                                @Value("${paktay.cors.allowed-origin-patterns:}") String corsOrigins) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.push = push;
        this.keycloak = keycloak;
        this.corsOrigins = corsOrigins;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.authHealth = RestClient.builder().baseUrl(authHealthUrl).requestFactory(factory).build();
    }

    // ---------------------------------------------------------------- resumen

    public AdminInsights.Metrics metrics() {
        long activeUsers = count("select count(*) from app_users where status = 'ACTIVE'");
        long newUsers = count("select count(*) from app_users where created_at >= " + MONTH_START);
        long captures = count("select count(*) from expenses where origin = 'AUTOMATIC' and kind = 'EXPENSE' and occurred_at >= " + MONTH_START);
        long cards = count("select count(*) from cards where status::text = 'ACTIVE'");
        long pushSent = count("select count(*) from notification_log where sent_at >= " + MONTH_START);
        long pushDelivered = count("select count(*) from notification_log where delivered and sent_at >= " + MONTH_START);
        return new AdminInsights.Metrics(activeUsers, newUsers, captures, cards, pushDelivered, pushSent, plans(), signupsByWeek());
    }

    private List<AdminInsights.PlanCount> plans() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("""
                select coalesce(s.plan, 'PRO') as plan, coalesce(s.source, 'BETA') as source, count(*) as total
                  from app_users u
                  left join user_subscription s on s.user_id = u.id and s.status = 'ACTIVE'
                 where u.status = 'ACTIVE'
                 group by 1, 2
                """).query((rs, row) -> Map.entry(planKey(rs.getString("plan"), rs.getString("source")), rs.getLong("total")))
                .list().forEach(entry -> counts.merge(entry.getKey(), entry.getValue(), Long::sum));
        List<AdminInsights.PlanCount> plans = new ArrayList<>();
        counts.forEach((key, total) -> plans.add(new AdminInsights.PlanCount(key, planLabel(key), total)));
        plans.sort((a, b) -> Long.compare(b.count(), a.count()));
        return plans;
    }

    static String planKey(String plan, String source) {
        if ("FREE".equals(plan)) return "FREE";
        if ("DUO".equals(plan)) return "DUO";
        return switch (source) {
            case "BETA" -> "PRO_BETA";
            case "TESTER" -> "PRO_TESTER";
            default -> "PRO_STORE";
        };
    }

    static String planLabel(String key) {
        return switch (key) {
            case "PRO_BETA" -> "PRO · Beta";
            case "PRO_TESTER" -> "PRO · Tester";
            case "PRO_STORE" -> "PRO · Tiendas";
            default -> key;
        };
    }

    /** Altas de las últimas 8 semanas ISO (lunes a domingo), incluida la actual, con ceros donde no hubo. */
    private List<AdminInsights.WeekCount> signupsByWeek() {
        LocalDate thisMonday = LocalDate.now(PANEL_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstMonday = thisMonday.minusWeeks(7);
        Map<LocalDate, Long> byWeek = new HashMap<>();
        jdbc.sql("""
                select date_trunc('week', created_at at time zone 'America/Guayaquil')::date as week_start, count(*) as total
                  from app_users
                 where created_at >= (cast(:first as date)::timestamp at time zone 'America/Guayaquil')
                 group by 1
                """).param("first", firstMonday)
                .query((rs, row) -> Map.entry(rs.getObject("week_start", LocalDate.class), rs.getLong("total"))).list()
                .forEach(entry -> byWeek.put(entry.getKey(), entry.getValue()));
        List<AdminInsights.WeekCount> weeks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            LocalDate monday = firstMonday.plusWeeks(i);
            weeks.add(new AdminInsights.WeekCount("S" + monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), monday, byWeek.getOrDefault(monday, 0L)));
        }
        return weeks;
    }

    // ---------------------------------------------------------------- notificaciones

    public AdminInsights.NotificationSummary notificationSummary() {
        long sent = count("select count(*) from notification_log where sent_at >= " + MONTH_START);
        long delivered = count("select count(*) from notification_log where delivered and sent_at >= " + MONTH_START);
        long withPush = count("select count(*) from user_devices where push_token is not null");
        long total = count("select count(*) from user_devices");
        return new AdminInsights.NotificationSummary(sent, delivered, sent - delivered, withPush, total);
    }

    /** result: DELIVERED, FAILED o vacío (todos). Últimos 200 envíos. */
    public List<AdminInsights.NotificationLog> notifications(String result) {
        String filter = "DELIVERED".equals(result) ? " where n.delivered" : "FAILED".equals(result) ? " where not n.delivered" : "";
        return jdbc.sql("""
                select n.id, n.kind, coalesce(u.email, '—') as email, n.threshold, to_char(n.period_month, 'YYYY-MM') as period,
                       n.delivered, n.sent_at
                  from notification_log n left join app_users u on u.id = n.user_id
                """ + filter + " order by n.sent_at desc limit 200")
                .query((rs, row) -> new AdminInsights.NotificationLog(rs.getObject("id", java.util.UUID.class),
                        rs.getString("kind"), rs.getString("email"), rs.getInt("threshold"), rs.getString("period"),
                        rs.getBoolean("delivered"), rs.getObject("sent_at", OffsetDateTime.class))).list();
    }

    /** Push de prueba a los dispositivos del propio administrador (con la app abierta con su cuenta). */
    public AdminInsights.TestPushResult testPush(AdminActor actor) {
        if (!push.enabled()) return new AdminInsights.TestPushResult(false, 0, "Firebase no está configurado en el servidor.");
        List<String> tokens = devices.pushTokens(actor.id());
        if (tokens.isEmpty()) {
            return new AdminInsights.TestPushResult(false, 0, "Tu cuenta no tiene dispositivos con avisos activados.");
        }
        int delivered = 0;
        for (String token : tokens) {
            PushSender.Outcome outcome = push.send(token, new PushSender.Message("PAKTAY",
                    "Aviso de prueba desde el panel de administración.", Map.of("type", "ADMIN_TEST")));
            if (outcome == PushSender.Outcome.SENT) delivered++;
            if (outcome == PushSender.Outcome.INVALID_TOKEN) devices.forgetPushToken(token);
        }
        return new AdminInsights.TestPushResult(delivered > 0, tokens.size(),
                delivered > 0 ? "Enviado a " + delivered + " de " + tokens.size() + " dispositivos." : "Firebase rechazó el envío.");
    }

    // ---------------------------------------------------------------- estado

    public AdminInsights.SystemStatus systemStatus() {
        List<AdminInsights.Service> services = new ArrayList<>();
        services.add(authService());
        long uptimeMinutes = ManagementFactory.getRuntimeMXBean().getUptime() / 60_000;
        services.add(new AdminInsights.Service("business-svc", "business-svc", "este servicio", "UP",
                uptimeMinutes + " min", "encendido"));
        services.add(keycloakService());
        String flyway = flywayVersion();
        services.add(databaseService(flyway));
        services.add(push.enabled()
                ? new AdminInsights.Service("fcm", "Firebase Cloud Messaging", "credenciales cargadas", "UP",
                        count("select count(*) from user_devices where push_token is not null") + " tokens", "dispositivos con avisos")
                : new AdminInsights.Service("fcm", "Firebase Cloud Messaging", "sin credenciales", "WARN", "apagado",
                        "no se envían avisos"));
        services.add(new AdminInsights.Service("smtp", "Correo (PIN y soporte)", "SMTP", "UNMONITORED", "sin chequeo",
                "health de correo desactivado"));

        List<AdminInsights.Job> jobs = List.of(
                new AdminInsights.Job("Purga de la bitácora del panel (> 90 días)", "diario 03:35"),
                new AdminInsights.Job("Purga de la bitácora vieja (> 90 días)", "diario 03:30"),
                new AdminInsights.Job("Tickets de soporte sin confirmar (> 48 h)", "diario 04:00"),
                new AdminInsights.Job("Reintento de avisos de presupuesto", "cada minuto"));

        List<AdminInsights.Warning> warnings = new ArrayList<>();
        if (count("select count(*) from app_users where lower(email) = 'admin@paktay.local'") > 0) {
            warnings.add(new AdminInsights.Warning("danger", "Usuario admin de ejemplo activo",
                    "admin@paktay.local sigue en uso; cámbialo o elimínalo antes de producción."));
        }
        if (corsOrigins != null && !corsOrigins.isBlank() && corsOrigins.contains("localhost") && !corsOrigins.contains("https://")) {
            warnings.add(new AdminInsights.Warning("warning", "CORS solo permite localhost",
                    "Agrega el dominio del panel a PAKTAY_CORS_ALLOWED_ORIGIN_PATTERNS al desplegar."));
        }
        if (!push.enabled()) {
            warnings.add(new AdminInsights.Warning("warning", "Avisos push apagados",
                    "Falta el archivo de credenciales de Firebase (FIREBASE_CREDENTIALS_HOST_FILE)."));
        }

        List<AdminInsights.Version> versions = List.of(
                new AdminInsights.Version("Esquema", flyway),
                new AdminInsights.Version("Java", System.getProperty("java.version")));
        return new AdminInsights.SystemStatus(OffsetDateTime.now(), services, jobs, warnings, versions);
    }

    private AdminInsights.Service authService() {
        long start = System.nanoTime();
        try {
            authHealth.get().retrieve().toBodilessEntity();
            return new AdminInsights.Service("auth-svc", "auth-svc", "identidad y cuentas", "UP", elapsed(start), "/actuator/health");
        } catch (Exception ex) {
            return new AdminInsights.Service("auth-svc", "auth-svc", "identidad y cuentas", "DOWN", "sin respuesta", "/actuator/health");
        }
    }

    private AdminInsights.Service keycloakService() {
        long start = System.nanoTime();
        boolean up = Status.UP.equals(keycloak.health().getStatus());
        return new AdminInsights.Service("keycloak", "Keycloak", "realm paktay", up ? "UP" : "DOWN",
                up ? elapsed(start) : "sin respuesta", "roles USER, ADMIN");
    }

    private AdminInsights.Service databaseService(String flyway) {
        long start = System.nanoTime();
        try {
            jdbc.sql("select 1").query(Integer.class).single();
            return new AdminInsights.Service("postgres", "PostgreSQL", flyway + " aplicada", "UP", elapsed(start), "base de negocio");
        } catch (Exception ex) {
            return new AdminInsights.Service("postgres", "PostgreSQL", "sin conexión", "DOWN", "sin respuesta", "base de negocio");
        }
    }

    private String flywayVersion() {
        try {
            return "Flyway V" + jdbc.sql("""
                    select version from flyway_schema_history where success and version is not null
                     order by installed_rank desc limit 1
                    """).query(String.class).optional().orElse("?");
        } catch (Exception ex) {
            return "Flyway ?";
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static String elapsed(long startNanos) {
        return ((System.nanoTime() - startNanos) / 1_000_000) + " ms";
    }
}
