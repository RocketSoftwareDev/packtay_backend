package ec.paktay.business.service;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.ExpenseResponse;
import ec.paktay.business.dto.MonthSummaryResponse;
import ec.paktay.business.dto.SummaryCardResponse;
import ec.paktay.business.dto.SummaryCategoryResponse;
import ec.paktay.business.dto.SummaryCountsResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resumen del mes para la pantalla de inicio. Lee las tablas y delega todo el
 * cálculo en {@link SummaryMath}.
 *
 * - Mes: calendario del usuario (app_users.timezone); los límites se pasan a la
 *   base como instantes.
 * - Presupuesto: user_budget_settings.global_amount (monto por defecto de cada
 *   categoría) + user_category_budgets activas del período (individual_amount
 *   null = usa el global). Mes actual: el mismo período que GET /budgets/current,
 *   que lo crea y hereda la plantilla MONTHLY. Mes pasado: sólo lectura, ver
 *   {@link BudgetService#budgetPeriodFor}.
 * - Gastado: gastos ACTIVE de tipo EXPENSE del mes en la moneda del presupuesto;
 *   los de otra moneda sólo se cuentan en otherCurrencyCount.
 */
@Service
public class SummaryService {
    static final int RECENT_LIMIT = 3;
    static final String DEFAULT_CURRENCY = "USD";

    private final JdbcClient jdbc;
    private final UserAccountService users;
    private final BudgetService budgets;

    public SummaryService(JdbcClient jdbc, UserAccountService users, BudgetService budgets) {
        this.jdbc = jdbc;
        this.users = users;
        this.budgets = budgets;
    }

    @Transactional
    public MonthSummaryResponse summary(UUID userId, String rawMonth) {
        users.ensureActiveUser(userId);
        ZoneId zone = users.zoneOf(userId);
        LocalDate today = LocalDate.now(zone);
        YearMonth month = SummaryMath.resolveMonth(rawMonth, today);
        SummaryMath.MonthClock clock = SummaryMath.clock(month, today);
        LocalDate firstDay = month.atDay(1);
        OffsetDateTime from = firstDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = firstDay.plusMonths(1).atStartOfDay(zone).toOffsetDateTime();

        UUID budgetPeriod = clock.current()
                ? budgets.ensureCurrentPeriod(userId)
                : budgets.budgetPeriodFor(userId, firstDay).orElse(null);
        BudgetSettings settings = settings(userId, budgetPeriod);
        String currency = settings.currency();
        BigDecimal global = settings.globalAmount();

        List<CategoryRow> categoryRows = categoryRows(userId, budgetPeriod, currency, from, to);
        List<SummaryMath.CategoryBudget> budgetInputs = new ArrayList<>();
        List<SummaryCategoryResponse> categories = new ArrayList<>();
        BigDecimal spent = BigDecimal.ZERO;
        for (CategoryRow row : categoryRows) {
            spent = spent.add(row.spent());
            budgetInputs.add(new SummaryMath.CategoryBudget(row.active(), row.selected(), row.ownAmount()));
            // Sólo una categoría activa y seleccionada tiene presupuesto; el resto sale por su gasto.
            boolean budgeted = row.active() && row.selected();
            BigDecimal own = budgeted ? row.ownAmount() : null;
            BigDecimal categoryGlobal = budgeted ? global : null;
            BigDecimal categoryBudget = SummaryMath.effectiveBudget(own, categoryGlobal);
            categories.add(new SummaryCategoryResponse(row.id(), row.name(), row.icon(), row.colorDark(), row.colorLight(),
                    categoryBudget, SummaryMath.budgetSource(own, categoryGlobal), row.spent(),
                    SummaryMath.percent(row.spent(), categoryBudget), SummaryMath.overBy(row.spent(), categoryBudget),
                    SummaryMath.categoryStatus(row.spent(), categoryBudget)));
        }
        categories.sort(Comparator.comparing(SummaryCategoryResponse::spent).reversed()
                .thenComparing(SummaryCategoryResponse::name, String.CASE_INSENSITIVE_ORDER));

        BigDecimal budget = SummaryMath.totalBudget(budgetInputs, global);

        return new MonthSummaryResponse(month.toString(), zone.getId(), currency,
                clock.daysInMonth(), clock.dayOfMonth(), clock.daysLeft(), clock.resetsOn(),
                budget, spent, SummaryMath.available(spent, budget), SummaryMath.percent(spent, budget),
                clock.elapsedPercent(), SummaryMath.pace(spent, budget, clock.elapsedPercent()),
                SummaryMath.overBy(spent, budget), otherCurrencyCount(userId, currency, from, to),
                categories, cards(userId, currency, firstDay, from, to), recent(userId, from, to), counts(userId));
    }

    private BudgetSettings settings(UUID userId, UUID periodId) {
        if (periodId == null) return new BudgetSettings(null, DEFAULT_CURRENCY);
        return jdbc.sql("""
                select global_amount, currency_code from user_budget_settings
                 where user_id = :userId and period_id = :periodId
                """).param("userId", userId).param("periodId", periodId)
                .query((rs, rowNum) -> new BudgetSettings(rs.getBigDecimal("global_amount"), rs.getString("currency_code").trim()))
                .optional().orElse(new BudgetSettings(null, DEFAULT_CURRENCY));
    }

    /**
     * Categorías con gasto en el mes (activas o no) más las activas seleccionadas
     * en el presupuesto aunque no tengan gasto.
     */
    private List<CategoryRow> categoryRows(UUID userId, UUID periodId, String currency,
                                           OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                with spend as (
                    select e.category_id, sum(e.amount) as spent
                      from expenses e
                     where e.user_id = :userId and e.status = 'ACTIVE' and e.kind = 'EXPENSE'
                       and e.currency_code = :currency
                       and e.occurred_at >= :from and e.occurred_at < :to
                     group by e.category_id
                ), selected as (
                    select b.category_id, b.individual_amount
                      from user_category_budgets b
                     where b.user_id = :userId and b.period_id = :periodId::uuid and b.active
                )
                select uc.id, coalesce(nullif(btrim(uc.alias), ''), uc.name) as display_name,
                       uc.icon, uc.color_dark, uc.color_light, uc.active,
                       (sel.category_id is not null) as selected, sel.individual_amount,
                       coalesce(sp.spent, 0) as spent
                  from user_categories uc
                  left join spend sp on sp.category_id = uc.id
                  left join selected sel on sel.category_id = uc.id
                 where uc.user_id = :userId
                   and (sp.category_id is not null or (sel.category_id is not null and uc.active))
                """).param("userId", userId).param("periodId", periodId, Types.OTHER)
                .param("currency", currency).param("from", from).param("to", to)
                .query((rs, rowNum) -> new CategoryRow(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("icon"), rs.getString("color_dark"), rs.getString("color_light"),
                        rs.getBoolean("active"), rs.getBoolean("selected"), rs.getBigDecimal("individual_amount"),
                        rs.getBigDecimal("spent"))).list();
    }

    /**
     * Tarjetas ACTIVE más las INACTIVE/DELETED con gasto en el mes. ownLimit es el
     * budget_allocations de alcance CARD del período de ese mes, si existe.
     */
    private List<SummaryCardResponse> cards(UUID userId, String currency, LocalDate firstDay,
                                            OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                with spend as (
                    select e.card_id, sum(e.amount) as spent
                      from expenses e
                     where e.user_id = :userId and e.status = 'ACTIVE' and e.kind = 'EXPENSE'
                       and e.currency_code = :currency
                       and e.occurred_at >= :from and e.occurred_at < :to
                     group by e.card_id
                )
                select c.id, c.alias, c.name as wallet_name, b.name as bank_name, b.logo_url as bank_logo_url,
                       c.status::text as status, coalesce(sp.spent, 0) as spent, ba.amount as own_limit
                  from cards c
                  join banks b on b.id = c.bank_id
                  left join spend sp on sp.card_id = c.id
                  left join financial_periods fp on fp.user_id = c.user_id and fp.period_month = :month
                  left join budget_allocations ba on ba.period_id = fp.id and ba.card_id = c.id
                       and ba.scope = 'CARD' and ba.active
                 where c.user_id = :userId
                   and (c.status = 'ACTIVE' or sp.card_id is not null)
                 order by (c.status = 'ACTIVE') desc, coalesce(sp.spent, 0) desc, c.created_at desc, c.id
                """).param("userId", userId).param("currency", currency).param("month", firstDay)
                .param("from", from).param("to", to)
                .query((rs, rowNum) -> new SummaryCardResponse(rs.getObject("id", UUID.class), rs.getString("alias"),
                        rs.getString("wallet_name"), rs.getString("bank_name"), rs.getString("bank_logo_url"),
                        rs.getString("status"), rs.getBigDecimal("spent"), rs.getBigDecimal("own_limit"))).list();
    }

    /** Los últimos gastos ACTIVE/EXPENSE del mes (cualquier moneda), con el único builder de ExpenseResponse. */
    private List<ExpenseResponse> recent(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql(ExpenseQueryService.SELECT_EXPENSE + """
                 where e.user_id = :userId and e.status = 'ACTIVE' and e.kind = 'EXPENSE'
                   and e.occurred_at >= :from and e.occurred_at < :to
                 order by e.occurred_at desc, e.id desc
                 limit :limit
                """).param("userId", userId).param("from", from).param("to", to).param("limit", RECENT_LIMIT)
                .query(ExpenseQueryService::mapRow).list();
    }

    private int otherCurrencyCount(UUID userId, String currency, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                select count(*) from expenses e
                 where e.user_id = :userId and e.status = 'ACTIVE' and e.kind = 'EXPENSE'
                   and e.currency_code <> :currency
                   and e.occurred_at >= :from and e.occurred_at < :to
                """).param("userId", userId).param("currency", currency).param("from", from).param("to", to)
                .query(Integer.class).single();
    }

    private SummaryCountsResponse counts(UUID userId) {
        return jdbc.sql("""
                select (select count(*) from user_categories where user_id = :userId and active) as active_categories,
                       (select count(*) from cards where user_id = :userId and status = 'ACTIVE') as active_cards
                """).param("userId", userId)
                .query((rs, rowNum) -> new SummaryCountsResponse(rs.getInt("active_categories"), rs.getInt("active_cards")))
                .single();
    }

    private record BudgetSettings(BigDecimal globalAmount, String currency) { }

    private record CategoryRow(UUID id, String name, String icon, String colorDark, String colorLight,
                               boolean active, boolean selected, BigDecimal ownAmount, BigDecimal spent) { }
}
