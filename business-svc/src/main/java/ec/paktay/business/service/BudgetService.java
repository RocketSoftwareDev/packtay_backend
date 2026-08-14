package ec.paktay.business.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
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
        if (request.globalAmount() == null
                && categories.stream().anyMatch(item -> item.individualAmount() == null)) {
            throw new IllegalArgumentException("Las categorías con presupuesto global requieren un globalAmount");
        }
        HashSet<UUID> unique = new HashSet<>();
        for (CategoryBudgetInput item : categories) {
            if (!unique.add(item.categoryId())) throw new IllegalArgumentException("No se puede repetir una categoría");
            ensureCategory(userId, item.categoryId());
        }
        ensureCurrency(request.currencyCode());
        UUID periodId = currentPeriod(userId);
        jdbc.sql("""
                insert into user_budget_settings (user_id, period_id, global_amount, currency_code)
                values (:userId, :periodId, :amount, :currency)
                on conflict (user_id, period_id) do update set global_amount = excluded.global_amount,
                    currency_code = excluded.currency_code, updated_at = now()
                """).param("userId", userId).param("periodId", periodId)
                .param("amount", request.globalAmount()).param("currency", request.currencyCode()).update();
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

    private BudgetResponse response(UUID userId, UUID periodId) {
        Settings settings = jdbc.sql("""
                select fp.period_month, s.global_amount, coalesce(s.currency_code, 'USD') currency_code
                  from financial_periods fp left join user_budget_settings s on s.period_id = fp.id and s.user_id = fp.user_id
                 where fp.id = :periodId and fp.user_id = :userId
                """).param("periodId", periodId).param("userId", userId).query((rs, rowNum) ->
                        new Settings(rs.getObject("period_month", LocalDate.class), rs.getBigDecimal("global_amount"),
                                rs.getString("currency_code"))).single();
        List<CategoryBudgetResponse> categories = jdbc.sql("""
                select uc.id, uc.alias, uc.icon, uc.color_dark, uc.color_light, b.individual_amount, b.active
                  from user_category_budgets b join user_categories uc on uc.id = b.category_id
                 where b.user_id = :userId and b.period_id = :periodId and b.active
                 order by uc.sort_order, uc.alias
                """).param("userId", userId).param("periodId", periodId).query((rs, rowNum) ->
                        new CategoryBudgetResponse(rs.getObject("id", UUID.class), rs.getString("alias"),
                                rs.getString("icon"), rs.getString("color_dark"), rs.getString("color_light"),
                                rs.getBigDecimal("individual_amount"), rs.getBoolean("active"))).list();
        return new BudgetResponse(settings.month(), settings.globalAmount(), settings.currency(), categories);
    }

    private UUID currentPeriod(UUID userId) {
        return jdbc.sql("""
                insert into financial_periods (user_id, period_month)
                values (:userId, date_trunc('month', current_date)::date)
                on conflict (user_id, period_month) do update set period_month = excluded.period_month
                returning id
                """).param("userId", userId).query(UUID.class).single();
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

    private record Settings(LocalDate month, BigDecimal globalAmount, String currency) { }
}
