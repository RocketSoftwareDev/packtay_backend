package ec.paktay.business.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ec.paktay.business.dto.BudgetResponse;
import ec.paktay.business.dto.CategoryBudgetInput;
import ec.paktay.business.dto.CategoryBudgetResponse;
import ec.paktay.business.dto.SaveBudgetRequest;
import ec.paktay.business.dto.UpdateCategoryBudgetRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetService {
    private final JdbcClient jdbc;
    private final UserAccountService users;

    public BudgetService(JdbcClient jdbc, UserAccountService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional
    public BudgetResponse current(UUID userId) {
        users.ensureActiveUser(userId);
        UUID periodId = currentPeriod(userId);
        return response(userId, periodId);
    }

    @Transactional
    public BudgetResponse save(UUID userId, SaveBudgetRequest request) {
        users.ensureActiveUser(userId);
        List<CategoryBudgetInput> categories = request.categories() == null ? List.of() : request.categories();
        HashSet<UUID> unique = new HashSet<>();
        for (CategoryBudgetInput item : categories) {
            if (!unique.add(item.categoryId())) throw new IllegalArgumentException("No se puede repetir una categoría");
            ensureCategory(userId, item.categoryId());
        }
        ensureCurrency(request.currencyCode());
        UUID periodId = currentPeriod(userId);
        jdbc.sql("""
                insert into user_budget_settings (user_id, period_id, global_amount, currency_code, recurrence)
                values (:userId, :periodId, :amount, :currency, :recurrence)
                on conflict (user_id, period_id) do update set global_amount = excluded.global_amount,
                    currency_code = excluded.currency_code, recurrence = excluded.recurrence, updated_at = now()
                """).param("userId", userId).param("periodId", periodId)
                .param("amount", request.globalAmount()).param("currency", request.currencyCode())
                .param("recurrence", request.recurrence()).update();
        jdbc.sql("update user_category_budgets set active = false, updated_at = now() where user_id = :userId and period_id = :periodId")
                .param("userId", userId).param("periodId", periodId).update();
        for (CategoryBudgetInput item : categories) {
            upsert(userId, periodId, item.categoryId(), item.individualAmount(), true);
        }
        return response(userId, periodId);
    }

    @Transactional
    public BudgetResponse updateCategory(UUID userId, UUID categoryId, UpdateCategoryBudgetRequest request) {
        users.ensureActiveUser(userId);
        ensureCategory(userId, categoryId);
        UUID periodId = currentPeriod(userId);
        upsert(userId, periodId, categoryId, request.individualAmount(), request.active());
        return response(userId, periodId);
    }

    private void upsert(UUID userId, UUID periodId, UUID categoryId, BigDecimal amount, boolean active) {
        jdbc.sql("""
                insert into user_category_budgets (user_id, period_id, category_id, individual_amount, active)
                values (:userId, :periodId, :categoryId, :amount, :active)
                on conflict (user_id, period_id, category_id) do update set
                    individual_amount = excluded.individual_amount, active = excluded.active, updated_at = now()
                """).param("userId", userId).param("periodId", periodId).param("categoryId", categoryId)
                .param("amount", amount).param("active", active).update();
    }

    /**
     * Presupuesto del período con lo gastado. El mes del período es un mes del
     * calendario del usuario: sus límites se convierten a instantes con
     * app_users.timezone antes de compararlos con occurred_at (timestamptz).
     * Sólo suman gastos ACTIVE de tipo EXPENSE en la moneda del presupuesto
     * (misma regla que GET /api/v1/user/summary). Los gastos de tarjetas
     * desactivadas o eliminadas siguen sumando.
     *
     * Totales (día 5, SummaryMath): cada categoría seleccionada y activa vale su
     * monto propio o, si no tiene, el global; budgetAmount es la suma de esos
     * efectivos. Las categorías desactivadas (user_categories.active = false) ya
     * no se listan ni suman aunque sigan marcadas en user_category_budgets.
     */
    private BudgetResponse response(UUID userId, UUID periodId) {
        Settings settings = jdbc.sql("""
                select fp.period_month, s.global_amount, coalesce(s.currency_code, 'USD') currency_code,
                       coalesce((select sum(e.amount) from expenses e
                                  where e.user_id = :userId
                                    and e.status = 'ACTIVE' and e.kind = 'EXPENSE'
                                    and e.currency_code = coalesce(s.currency_code, 'USD')
                                    and e.occurred_at >= (fp.period_month::timestamp at time zone u.timezone)
                                    and e.occurred_at < ((fp.period_month + interval '1 month') at time zone u.timezone)), 0) spent_amount,
                       coalesce(s.recurrence, 'THIS_MONTH') recurrence
                  from financial_periods fp
                  join app_users u on u.id = fp.user_id
                  left join user_budget_settings s on s.period_id = fp.id and s.user_id = fp.user_id
                 where fp.id = :periodId and fp.user_id = :userId
                """).param("periodId", periodId).param("userId", userId).query((rs, rowNum) ->
                        new Settings(rs.getObject("period_month", LocalDate.class), rs.getBigDecimal("global_amount"),
                                rs.getString("currency_code"), rs.getString("recurrence"), rs.getBigDecimal("spent_amount"))).single();
        BigDecimal global = settings.globalAmount();
        List<CategoryBudgetResponse> categories = jdbc.sql("""
                select uc.id, uc.alias, uc.icon, uc.color_dark, uc.color_light, b.individual_amount, b.active,
                       coalesce((select sum(e.amount) from expenses e
                                  where e.user_id = :userId and e.category_id = uc.id
                                    and e.status = 'ACTIVE' and e.kind = 'EXPENSE'
                                    and e.currency_code = :currency
                                    and e.occurred_at >= (fp.period_month::timestamp at time zone u.timezone)
                                    and e.occurred_at < ((fp.period_month + interval '1 month') at time zone u.timezone)), 0) spent_amount
                  from user_category_budgets b
                  join user_categories uc on uc.id = b.category_id
                  join financial_periods fp on fp.id = b.period_id
                  join app_users u on u.id = b.user_id
                 where b.user_id = :userId and b.period_id = :periodId and b.active and uc.active
                 order by uc.sort_order, uc.alias
                """).param("userId", userId).param("periodId", periodId).param("currency", settings.currency())
                .query((rs, rowNum) -> {
                    BigDecimal own = rs.getBigDecimal("individual_amount");
                    BigDecimal spent = rs.getBigDecimal("spent_amount");
                    BigDecimal effective = SummaryMath.effectiveBudget(own, global);
                    return new CategoryBudgetResponse(rs.getObject("id", UUID.class), rs.getString("alias"),
                            rs.getString("icon"), rs.getString("color_dark"), rs.getString("color_light"),
                            own, rs.getBoolean("active"), spent, effective,
                            SummaryMath.budgetSource(own, global), SummaryMath.percent(spent, effective),
                            SummaryMath.categoryStatus(spent, effective));
                }).list();
        BigDecimal budget = SummaryMath.totalBudget(categories.stream()
                .map(c -> new SummaryMath.CategoryBudget(true, true, c.individualAmount())).toList(), global);
        return new BudgetResponse(settings.month(), global, settings.currency(), settings.recurrence(),
                settings.spentAmount(), categories, budget,
                SummaryMath.available(settings.spentAmount(), budget),
                SummaryMath.percent(settings.spentAmount(), budget));
    }

    /**
     * Período del mes actual del usuario, creado si falta y con la plantilla MONTHLY
     * heredada. Lo usa el resumen del mes para leer el mismo presupuesto que
     * GET /budgets/current.
     */
    public UUID ensureCurrentPeriod(UUID userId) {
        return currentPeriod(userId);
    }

    /**
     * Período cuyo presupuesto rige un mes pasado, sin crear nada: el del propio mes
     * si tiene configuración (user_budget_settings o categorías seleccionadas); si
     * no, el último período anterior con recurrence = MONTHLY, que es lo que
     * currentPeriod habría copiado si el usuario hubiera abierto la app ese mes.
     * Vacío si el mes no tiene presupuesto.
     */
    public Optional<UUID> budgetPeriodFor(UUID userId, LocalDate periodMonth) {
        return jdbc.sql("""
                select id from (
                    select fp.id, 0 as priority, fp.period_month
                      from financial_periods fp
                     where fp.user_id = :userId and fp.period_month = :month
                       and (exists (select 1 from user_budget_settings s where s.period_id = fp.id and s.user_id = :userId)
                            or exists (select 1 from user_category_budgets b where b.period_id = fp.id and b.user_id = :userId))
                    union all
                    select fp.id, 1 as priority, fp.period_month
                      from financial_periods fp
                      join user_budget_settings s on s.period_id = fp.id and s.user_id = fp.user_id
                     where fp.user_id = :userId and fp.period_month < :month and s.recurrence = 'MONTHLY'
                ) candidates
                 order by priority, period_month desc
                 limit 1
                """).param("userId", userId).param("month", periodMonth).query(UUID.class).optional();
    }

    /**
     * Período del mes actual en la zona horaria del usuario (no en la del servidor),
     * creado si aún no existe, y copia de la plantilla MONTHLY del período anterior.
     */
    private UUID currentPeriod(UUID userId) {
        UUID periodId = jdbc.sql("""
                insert into financial_periods (user_id, period_month)
                select u.id, date_trunc('month', now() at time zone u.timezone)::date
                  from app_users u where u.id = :userId
                on conflict (user_id, period_month) do update set period_month = excluded.period_month
                returning id
                """).param("userId", userId).query(UUID.class).single();
        jdbc.sql("""
                with previous as (
                    select s.global_amount, s.currency_code, s.recurrence, s.period_id
                      from user_budget_settings s join financial_periods fp on fp.id = s.period_id
                     where s.user_id = :userId
                       and fp.period_month < (select cur.period_month from financial_periods cur where cur.id = :periodId)
                       and s.recurrence = 'MONTHLY'
                     order by fp.period_month desc limit 1
                ), copied as (
                    insert into user_budget_settings (user_id, period_id, global_amount, currency_code, recurrence)
                    select :userId, :periodId, global_amount, currency_code, recurrence from previous
                    on conflict (user_id, period_id) do nothing returning 1
                )
                insert into user_category_budgets (user_id, period_id, category_id, individual_amount, active)
                select :userId, :periodId, b.category_id, b.individual_amount, b.active
                  from previous p join user_category_budgets b on b.period_id = p.period_id and b.user_id = :userId
                 where exists(select 1 from copied)
                on conflict (user_id, period_id, category_id) do nothing
                """).param("userId", userId).param("periodId", periodId).update();
        return periodId;
    }

    private void ensureCategory(UUID userId, UUID categoryId) {
        boolean exists = jdbc.sql("select exists(select 1 from user_categories where id=:id and user_id=:userId and active)")
                .param("id", categoryId).param("userId", userId).query(Boolean.class).single();
        if (!exists) throw new IllegalArgumentException("La categoría no existe, está inactiva o no pertenece al usuario");
    }

    private void ensureCurrency(String currency) {
        boolean exists = jdbc.sql("select exists(select 1 from currencies where code=:code and active)")
                .param("code", currency).query(Boolean.class).single();
        if (!exists) throw new IllegalArgumentException("La moneda no existe o está inactiva");
    }

    private record Settings(LocalDate month, BigDecimal globalAmount, String currency, String recurrence,
                             BigDecimal spentAmount) { }
}
