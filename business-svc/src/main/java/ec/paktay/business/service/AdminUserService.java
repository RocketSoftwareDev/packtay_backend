package ec.paktay.business.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.admin.AdminUserDetail;
import ec.paktay.business.dto.admin.AdminUserSummary;
import ec.paktay.business.dto.admin.PageResponse;
import ec.paktay.business.exception.ConflictException;
import ec.paktay.business.exception.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usuarios vistos desde el panel. Perfil, plan, uso del mes (conteos), dispositivos y
 * consentimientos; nunca gastos, montos ni categorías (regla de privacidad del panel).
 * El bloqueo y el borrado viven en auth-svc porque también tocan Keycloak.
 */
@Service
public class AdminUserService {
    static final int MAX_PAGE_SIZE = 50;

    /** Columnas comunes a la lista y al detalle. */
    private static final String BASE = """
            select u.id, coalesce(nullif(u.display_name, ''), u.email) as name, u.email, u.country_code,
                   u.timezone, u.created_at, coalesce(s.plan, 'PRO') as plan, s.source as plan_source,
                   s.current_period_end,
                   case when u.status = 'ACTIVE' then 'ACTIVE' else 'BLOCKED' end as status,
                   (select count(*) from cards c where c.user_id = u.id and c.status::text = 'ACTIVE') as active_cards,
                   d.device_name, d.last_authenticated_at
              from app_users u
              left join user_subscription s on s.user_id = u.id and s.status = 'ACTIVE'
              left join lateral (
                    select device_name, last_authenticated_at from user_devices
                     where user_id = u.id order by last_authenticated_at desc nulls last limit 1
              ) d on true
            """;

    private final JdbcClient jdbc;
    private final PlanService plans;
    private final AdminAuditService audit;

    public AdminUserService(JdbcClient jdbc, PlanService plans, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.audit = audit;
    }

    public PageResponse<AdminUserSummary> list(String query, String plan, String status, String country, int page, int size) {
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageNumber = Math.max(1, page);
        String pattern = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
        AtomicLong total = new AtomicLong();
        List<AdminUserSummary> items = jdbc.sql("select *, count(*) over () as total from (" + BASE + """
                ) base
                 where (cast(:pattern as text) is null or email ilike cast(:pattern as text) or name ilike cast(:pattern as text))
                   and (cast(:plan as text) is null or plan = cast(:plan as text))
                   and (cast(:status as text) is null or status = cast(:status as text))
                   and (cast(:country as text) is null or country_code = cast(:country as text))
                 order by created_at desc
                 limit :limit offset :offset
                """)
                .param("pattern", pattern, java.sql.Types.VARCHAR).param("plan", blankToNull(plan), java.sql.Types.VARCHAR)
                .param("status", blankToNull(status), java.sql.Types.VARCHAR)
                .param("country", blankToNull(country), java.sql.Types.VARCHAR)
                .param("limit", pageSize).param("offset", (pageNumber - 1) * pageSize)
                .query((rs, row) -> {
                    total.set(rs.getLong("total"));
                    return summary(rs);
                }).list();
        return new PageResponse<>(items, total.get(), pageNumber, pageSize);
    }

    public AdminUserDetail detail(UUID userId) {
        Row row = jdbc.sql(BASE + " where u.id = :id").param("id", userId)
                .query((rs, index) -> new Row(summary(rs), rs.getString("timezone"),
                        rs.getObject("current_period_end", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new NotFoundException("El usuario no existe"));
        AdminUserSummary base = row.summary();
        boolean free = "FREE".equals(base.plan());
        AdminUserDetail.Usage usage = new AdminUserDetail.Usage(base.activeCards(), free ? PlanService.FREE_CARDS : null,
                plans.capturesThisMonth(userId), free ? PlanService.FREE_CAPTURES_PER_MONTH : null, budgetCategories(userId));
        return new AdminUserDetail(base.id(), base.name(), base.email(), base.country(), base.plan(), base.planSource(),
                base.status(), base.activeCards(), base.lastAccess(), base.createdAt(), row.timezone(),
                new AdminUserDetail.Subscription(base.plan(), base.planSource(), row.periodEnd()),
                usage, devices(userId), consents(userId));
    }

    private record Row(AdminUserSummary summary, String timezone, OffsetDateTime periodEnd) {
    }

    /**
     * Cambio manual de plan (fuente TESTER). Una suscripción de la tienda no se toca desde
     * aquí: el siguiente webhook de la tienda la volvería a pisar.
     */
    @Transactional
    public AdminUserDetail changePlan(UUID userId, String plan, AdminActor actor) {
        AdminUserDetail current = detail(userId);
        String source = current.planSource();
        if ("APP_STORE".equals(source) || "PLAY_STORE".equals(source)) {
            throw new ConflictException("El plan viene de la tienda; se cambia desde la suscripción del usuario.");
        }
        if (plan.equals(current.plan()) && "TESTER".equals(source)) return current;
        jdbc.sql("""
                insert into user_subscription (user_id, plan, source, status, updated_at)
                values (:id, :plan, 'TESTER', 'ACTIVE', now())
                on conflict (user_id) do update
                   set plan = excluded.plan, source = 'TESTER', status = 'ACTIVE',
                       store_product_id = null, current_period_end = null, updated_at = now()
                """).param("id", userId).param("plan", plan).update();
        audit.adminAction(actor, "Plan cambiado", userId, labelOf(current),
                Map.of("plan", current.plan(), "source", source == null ? "BETA" : source),
                Map.of("plan", plan, "source", "TESTER"));
        return detail(userId);
    }

    private AdminUserSummary summary(ResultSet rs) throws SQLException {
        OffsetDateTime lastAt = rs.getObject("last_authenticated_at", OffsetDateTime.class);
        String device = rs.getString("device_name");
        AdminUserSummary.LastAccess last = lastAt == null ? null
                : new AdminUserSummary.LastAccess(device == null || device.isBlank() ? "Dispositivo" : device, lastAt);
        return new AdminUserSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
                rs.getString("country_code"), rs.getString("plan"), rs.getString("plan_source"), rs.getString("status"),
                rs.getInt("active_cards"), last, rs.getObject("created_at", OffsetDateTime.class));
    }

    /** Categorías con presupuesto activo en el mes actual del usuario (solo el conteo). */
    private int budgetCategories(UUID userId) {
        return jdbc.sql("""
                select count(*) from user_category_budgets b
                  join user_categories uc on uc.id = b.category_id
                  join financial_periods p on p.id = b.period_id
                  join app_users u on u.id = b.user_id
                 where b.user_id = :id and b.active and uc.active
                   and p.period_month = (date_trunc('month', now() at time zone u.timezone))::date
                """).param("id", userId).query(Integer.class).single();
    }

    private List<AdminUserDetail.Device> devices(UUID userId) {
        return jdbc.sql("""
                select id, device_name, platform, last_authenticated_at, biometric_enabled, push_token is not null as push_enabled
                  from user_devices where user_id = :id order by last_authenticated_at desc nulls last
                """).param("id", userId).query((rs, row) -> new AdminUserDetail.Device(rs.getObject("id", UUID.class),
                rs.getString("device_name"), rs.getString("platform"), rs.getObject("last_authenticated_at", OffsetDateTime.class),
                rs.getBoolean("biometric_enabled"), rs.getBoolean("push_enabled"))).list();
    }

    private List<AdminUserDetail.Consent> consents(UUID userId) {
        return jdbc.sql("select upper(document) as document, version, accepted_at from user_consent where user_id = :id order by accepted_at")
                .param("id", userId).query((rs, row) -> new AdminUserDetail.Consent(rs.getString("document"),
                        rs.getString("version"), rs.getObject("accepted_at", OffsetDateTime.class))).list();
    }

    static String labelOf(AdminUserDetail user) {
        return user.email() != null ? user.email() : user.id().toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value) ? null : value.trim();
    }
}
