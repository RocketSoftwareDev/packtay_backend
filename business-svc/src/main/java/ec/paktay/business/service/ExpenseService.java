package ec.paktay.business.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import ec.paktay.business.dto.CreateExpenseRequest;
import ec.paktay.business.dto.ExpenseResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService {
    private final JdbcClient jdbc;
    private final UserAccountService users;
    private final AuditService audit;

    public ExpenseService(JdbcClient jdbc, UserAccountService users, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    @Transactional
    public ExpenseResponse createManual(UUID userId, CreateExpenseRequest request) {
        users.ensureActiveUser(userId);
        validateRecurrence(request.recurring(), request.recurrenceDay());
        ExpenseResponse existing = findByIdempotency(userId, request.idempotencyKey());
        if (existing != null) return existing;
        ensureCard(userId, request.cardId());
        ensureCategory(userId, request.categoryId());
        ensureCurrency(request.currencyCode());
        BigDecimal rate = request.exchangeRateToUsd() == null ? BigDecimal.ONE : request.exchangeRateToUsd();
        boolean assignedByRule = Boolean.TRUE.equals(request.assignedByRule());
        UUID expenseId = jdbc.sql("""
                insert into expenses (user_id, idempotency_key, card_id, category_id, origin, amount,
                    currency_code, exchange_rate_to_usd, merchant_raw, merchant_normalized,
                    normalization_version, occurred_at, is_recurring, recurrence_day, assigned_by_rule)
                values (:userId, :key, :cardId, :categoryId, 'MANUAL', :amount, :currency, :rate,
                    :merchant, :normalized, 1, :occurredAt, :recurring, :recurrenceDay, :assignedByRule)
                returning id
                """).param("userId", userId).param("key", request.idempotencyKey())
                .param("cardId", request.cardId()).param("categoryId", request.categoryId())
                .param("amount", request.amount()).param("currency", request.currencyCode()).param("rate", rate)
                .param("merchant", request.merchant().trim()).param("normalized", normalize(request.merchant()))
                .param("occurredAt", request.occurredAt()).param("recurring", request.recurring())
                .param("recurrenceDay", request.recurrenceDay()).param("assignedByRule", assignedByRule)
                .query(UUID.class).single();
        remember(userId, request.merchant().trim(), normalize(request.merchant()), request.categoryId());
        audit.record(userId, "CREATE", "expense", expenseId,
                Map.of("origin", "MANUAL", "assignedByRule", assignedByRule));
        return findOne(userId, expenseId);
    }

    private void remember(UUID userId, String merchant, String normalized, UUID categoryId) {
        jdbc.sql("""
                insert into user_consumption_selections (user_id, consumption_name, merchant_normalized,
                    normalization_version, category_id)
                values (:userId, :name, :normalized, 1, :categoryId)
                on conflict (user_id, merchant_normalized, normalization_version) do update set
                    consumption_name = excluded.consumption_name, category_id = excluded.category_id,
                    active = true, selection_count = user_consumption_selections.selection_count + 1,
                    last_selected_at = now(), updated_at = now()
                """).param("userId", userId).param("name", merchant)
                .param("normalized", normalized).param("categoryId", categoryId).update();
    }

    private ExpenseResponse findByIdempotency(UUID userId, UUID key) {
        return jdbc.sql("select id from expenses where user_id = :userId and idempotency_key = :key")
                .param("userId", userId).param("key", key).query(UUID.class).optional()
                .map(id -> findOne(userId, id)).orElse(null);
    }

    private ExpenseResponse findOne(UUID userId, UUID expenseId) {
        return jdbc.sql(ExpenseQueryService.SELECT_EXPENSE + " where e.user_id = :userId and e.id = :expenseId")
                .param("userId", userId).param("expenseId", expenseId)
                .query(ExpenseQueryService::mapRow).single();
    }

    private void ensureCard(UUID userId, UUID cardId) {
        boolean exists = jdbc.sql("select exists(select 1 from cards where id = :id and user_id = :userId and status = 'ACTIVE')")
                .param("id", cardId).param("userId", userId).query(Boolean.class).single();
        if (!exists) throw new IllegalArgumentException("La tarjeta no existe, no pertenece al usuario o está inactiva");
    }

    private void ensureCategory(UUID userId, UUID categoryId) {
        boolean exists = jdbc.sql("select exists(select 1 from user_categories where id = :id and user_id = :userId and active)")
                .param("id", categoryId).param("userId", userId).query(Boolean.class).single();
        if (!exists) throw new IllegalArgumentException("La categoría no existe, no pertenece al usuario o está inactiva");
    }

    private void ensureCurrency(String currency) {
        boolean exists = jdbc.sql("select exists(select 1 from currencies where code = :code and active)")
                .param("code", currency).query(Boolean.class).single();
        if (!exists) throw new IllegalArgumentException("La moneda no existe o está inactiva");
    }

    private void validateRecurrence(boolean recurring, Integer day) {
        if ((recurring && (day == null || day < 1 || day > 31)) || (!recurring && day != null)) {
            throw new IllegalArgumentException("recurrenceDay debe estar entre 1 y 31 únicamente cuando recurring es true");
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
