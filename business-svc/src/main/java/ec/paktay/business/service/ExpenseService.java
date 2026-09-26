package ec.paktay.business.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ec.paktay.business.dto.CreateExpenseRequest;
import ec.paktay.business.dto.ExpenseResponse;
import ec.paktay.business.dto.UpdateExpenseRequest;
import ec.paktay.business.dto.VoidExpenseResponse;
import ec.paktay.business.exception.ConflictException;
import ec.paktay.business.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService {
    /** Prefijo del comercio del registro compensatorio. merchant_raw admite 180 caracteres. */
    static final String VOID_PREFIX = "Anulación: ";
    private static final int MERCHANT_MAX = 180;
    /** Dos capturas iguales con menos de esta diferencia son el mismo pago. */
    static final int DUPLICATE_WINDOW_SECONDS = 60;
    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    private final JdbcClient jdbc;
    private final UserAccountService users;
    private final ExpenseQueryService query;
    private final MerchantRuleService rules;
    private final PlanService plans;
    private final BudgetAlertService alerts;

    public ExpenseService(JdbcClient jdbc, UserAccountService users, ExpenseQueryService query,
                          MerchantRuleService rules, PlanService plans, BudgetAlertService alerts) {
        this.jdbc = jdbc;
        this.users = users;
        this.query = query;
        this.rules = rules;
        this.plans = plans;
        this.alerts = alerts;
    }

    /**
     * Alta idempotente por (user_id, idempotency_key), de un gasto manual o de una
     * captura de Wallet confirmada (origin AUTOMATIC).
     *
     * La carrera de dos reintentos simultáneos se resuelve con ON CONFLICT DO NOTHING
     * sobre el índice único parcial expenses_user_idempotency_uq, no atrapando
     * DuplicateKeyException: en PostgreSQL un error deja la transacción abortada y el
     * SELECT posterior fallaría. Si el INSERT no devuelve fila, otra transacción ya
     * confirmó ese gasto y se devuelve el existente con el mismo cuerpo.
     *
     * Sólo una captura de Wallet crea o actualiza la regla de su comercio: un
     * comercio escrito a mano generaría reglas con cada variante que se teclee.
     */
    @Transactional
    public ExpenseResponse create(UUID userId, CreateExpenseRequest request) {
        users.ensureActiveUser(userId);
        validateRecurrence(request.recurring(), request.recurrenceDay());
        ExpenseResponse existing = findByIdempotency(userId, request.idempotencyKey());
        if (existing != null) return existing;
        boolean automatic = request.automatic();
        String origin = automatic ? "AUTOMATIC" : "MANUAL";
        ensureCard(userId, request.cardId());
        ensureCategory(userId, request.categoryId());
        ensureCurrency(request.currencyCode());
        validateOriginal(request);
        if (!automatic) ManualDateWindow.validate(request.occurredAt(), users.zoneOf(userId), Instant.now());
        String merchant = request.merchant().trim();
        String normalized = MerchantKey.normalize(merchant);

        if (automatic) {
            Optional<UUID> duplicate = findDuplicateCapture(userId, request, normalized);
            if (duplicate.isPresent()) {
                // El mismo pago llegó dos veces: se guarda uno y el otro queda en el log
                // para que soporte vea por qué el atajo lo mandó repetido.
                log.warn("wallet_duplicate_discarded userId={} duplicateOf={}", userId, duplicate.get());
                return query.findOne(userId, duplicate.get());
            }
        }

        // Plan Gratis: la captura 21 del mes se rechaza (409) y el teléfono la deja bloqueada.
        if (automatic) plans.ensureCaptureRoom(userId);

        BigDecimal rate = request.exchangeRateToUsd() == null ? BigDecimal.ONE : request.exchangeRateToUsd();
        boolean assignedByRule = Boolean.TRUE.equals(request.assignedByRule());
        Optional<UUID> inserted = jdbc.sql("""
                insert into expenses (user_id, idempotency_key, card_id, category_id, origin, amount,
                    currency_code, exchange_rate_to_usd, merchant_raw, merchant_normalized,
                    normalization_version, occurred_at, is_recurring, recurrence_day, assigned_by_rule,
                    original_amount, original_currency_code, country_code)
                values (:userId, :key, :cardId, :categoryId, cast(:origin as expense_origin), :amount, :currency, :rate,
                    :merchant, :normalized, 1, :occurredAt, :recurring, :recurrenceDay, :assignedByRule,
                    :originalAmount, :originalCurrency, :country)
                on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing
                returning id
                """).param("userId", userId).param("key", request.idempotencyKey())
                .param("cardId", request.cardId()).param("categoryId", request.categoryId())
                .param("origin", origin)
                .param("amount", request.amount()).param("currency", request.currencyCode()).param("rate", rate)
                .param("merchant", merchant).param("normalized", normalized)
                .param("occurredAt", request.occurredAt()).param("recurring", request.recurring())
                .param("recurrenceDay", request.recurrenceDay()).param("assignedByRule", assignedByRule)
                .param("originalAmount", request.originalAmount(), java.sql.Types.NUMERIC)
                .param("originalCurrency", request.originalCurrencyCode(), java.sql.Types.CHAR)
                .param("country", request.countryCode(), java.sql.Types.CHAR)
                .query(UUID.class).optional();
        if (inserted.isEmpty()) {
            ExpenseResponse raced = findByIdempotency(userId, request.idempotencyKey());
            if (raced == null) throw new IllegalStateException("Conflicto de idempotencia sin gasto existente");
            return raced;
        }
        UUID expenseId = inserted.get();
        if (automatic) rules.remember(userId, merchant, request.categoryId());
        alerts.afterExpense(userId, request.categoryId(), request.cardId(), request.occurredAt());
        return query.findOne(userId, expenseId);
    }

    /**
     * Una captura de Wallet igual a otra ya guardada: mismo comercio, monto y
     * tarjeta con menos de {@link #DUPLICATE_WINDOW_SECONDS} segundos entre las dos.
     * El bloqueo consultivo serializa dos envíos simultáneos del mismo pago para que
     * los dos no pasen la comprobación a la vez.
     */
    private Optional<UUID> findDuplicateCapture(UUID userId, CreateExpenseRequest request, String normalized) {
        String lockKey = userId + "|" + request.cardId() + "|" + request.amount().stripTrailingZeros().toPlainString()
                + "|" + normalized;
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", lockKey)
                .query((rs, rowNum) -> 1).list();
        return jdbc.sql("""
                select id from expenses
                 where user_id = :userId and card_id = :cardId and kind = 'EXPENSE' and origin = 'AUTOMATIC'
                   and amount = :amount and merchant_normalized = :normalized
                   and occurred_at > cast(:occurredAt as timestamptz) - make_interval(secs => :window)
                   and occurred_at < cast(:occurredAt as timestamptz) + make_interval(secs => :window)
                 order by occurred_at
                 limit 1
                """).param("userId", userId).param("cardId", request.cardId()).param("amount", request.amount())
                .param("normalized", normalized).param("occurredAt", request.occurredAt())
                .param("window", DUPLICATE_WINDOW_SECONDS).query(UUID.class).optional();
    }

    private void validateOriginal(CreateExpenseRequest request) {
        boolean hasAmount = request.originalAmount() != null;
        boolean hasCurrency = request.originalCurrencyCode() != null;
        if (hasAmount != hasCurrency) {
            throw new IllegalArgumentException("originalAmount y originalCurrencyCode van juntos");
        }
        if (hasCurrency) {
            ensureCurrency(request.originalCurrencyCode());
            if (request.originalCurrencyCode().equals(request.currencyCode())) {
                throw new IllegalArgumentException("originalCurrencyCode sólo se envía si es distinta de currencyCode");
            }
        }
    }

    /**
     * Edición de un gasto propio. Sólo gastos ACTIVE de tipo EXPENSE.
     *
     * Categoría siempre. Tarjeta, monto y comercio sólo en gastos MANUAL: en una
     * captura de Wallet la tarjeta sale del nombre que mandó Wallet, y el monto y el
     * comercio son los del banco. Cambiar la categoría de una captura de Wallet
     * cambia la regla de su comercio desde el próximo pago; los gastos ya guardados
     * no se recalculan. Editar un gasto MANUAL nunca toca las reglas.
     */
    @Transactional
    public ExpenseResponse update(UUID userId, UUID expenseId, UpdateExpenseRequest request) {
        users.ensureActiveUser(userId);
        Current current = lockOwned(userId, expenseId);
        if (!"EXPENSE".equals(current.kind())) {
            throw new ConflictException("Un registro de anulación no se puede editar");
        }
        if (!"ACTIVE".equals(current.status())) {
            throw new ConflictException("Un gasto anulado no se puede editar");
        }
        boolean manual = "MANUAL".equals(current.origin());

        List<String> changed = new ArrayList<>();

        BigDecimal amount = current.amount();
        if (request.amount() != null && request.amount().compareTo(current.amount()) != 0) {
            if (!manual) throw new IllegalArgumentException("El monto de un gasto capturado por Wallet no se puede cambiar");
            amount = request.amount();
            changed.add("amount");
        }

        String merchant = current.merchantRaw();
        if (request.merchantRaw() != null) {
            String trimmed = request.merchantRaw().trim();
            if (trimmed.isEmpty()) throw new IllegalArgumentException("merchantRaw no puede estar vacío");
            if (!trimmed.equals(current.merchantRaw())) {
                if (!manual) throw new IllegalArgumentException("El comercio de un gasto capturado por Wallet no se puede cambiar");
                merchant = trimmed;
                changed.add("merchantRaw");
            }
        }

        if (!request.cardId().equals(current.cardId())) {
            if (!manual) throw new IllegalArgumentException("La tarjeta de un gasto capturado por Wallet no se puede cambiar");
            ensureCard(userId, request.cardId());
            changed.add("cardId");
        }
        if (!request.categoryId().equals(current.categoryId())) {
            ensureCategory(userId, request.categoryId());
            changed.add("categoryId");
        }

        if (changed.isEmpty()) return query.findOne(userId, expenseId);

        boolean merchantChanged = changed.contains("merchantRaw");
        jdbc.sql("""
                update expenses
                   set card_id = :cardId, category_id = :categoryId, amount = :amount,
                       merchant_raw = :merchant,
                       merchant_normalized = case when :merchantChanged then :normalized else merchant_normalized end,
                       normalization_version = case when :merchantChanged then 1 else normalization_version end
                 where id = :id and user_id = :userId
                """).param("cardId", request.cardId()).param("categoryId", request.categoryId())
                .param("amount", amount).param("merchant", merchant)
                .param("merchantChanged", merchantChanged).param("normalized", MerchantKey.normalize(merchant))
                .param("id", expenseId).param("userId", userId).update();

        if (!manual && changed.contains("categoryId")) {
            rules.remember(userId, current.merchantRaw(), request.categoryId());
        }
        if (changed.contains("categoryId") || changed.contains("cardId") || changed.contains("amount")) {
            alerts.afterExpense(userId, request.categoryId(), request.cardId(), current.occurredAt());
        }
        return query.findOne(userId, expenseId);
    }

    /**
     * Anulación: el original pasa a VOIDED y deja de contar, y se inserta un registro
     * REFUND ACTIVE con el mismo usuario, tarjeta, categoría, moneda, monto (positivo),
     * origen y fecha del original, para que quede en el mismo mes. Todo en una
     * transacción y con la fila original bloqueada (FOR UPDATE), así dos anulaciones
     * simultáneas no crean dos REFUND. Anular un gasto ya anulado devuelve el par
     * existente sin crear otro.
     */
    @Transactional
    public VoidExpenseResponse voidExpense(UUID userId, UUID expenseId) {
        users.ensureActiveUser(userId);
        Current current = lockOwned(userId, expenseId);
        if ("REFUND".equals(current.kind())) {
            throw new ConflictException("Un registro de anulación no se puede anular");
        }
        if ("VOIDED".equals(current.status())) {
            ExpenseResponse refund = current.voidedByExpenseId() == null ? null
                    : query.findOne(userId, current.voidedByExpenseId());
            return new VoidExpenseResponse(query.findOne(userId, expenseId), refund);
        }

        UUID refundId = jdbc.sql("""
                insert into expenses (user_id, idempotency_key, card_id, category_id, origin, amount,
                    currency_code, exchange_rate_to_usd, merchant_raw, merchant_normalized,
                    normalization_version, occurred_at, is_recurring, recurrence_day,
                    kind, status, assigned_by_rule, space_id)
                select e.user_id, gen_random_uuid(), e.card_id, e.category_id, e.origin, e.amount,
                       e.currency_code, e.exchange_rate_to_usd, left(:prefix || e.merchant_raw, :merchantMax),
                       e.merchant_normalized, e.normalization_version, e.occurred_at, false, null::smallint,
                       'REFUND', 'ACTIVE', false, e.space_id
                  from expenses e
                 where e.id = :id and e.user_id = :userId
                returning id
                """).param("prefix", VOID_PREFIX).param("merchantMax", MERCHANT_MAX)
                .param("id", expenseId).param("userId", userId)
                .query(UUID.class).single();

        jdbc.sql("""
                update expenses set status = 'VOIDED', voided_by_expense_id = :refundId
                 where id = :id and user_id = :userId
                """).param("refundId", refundId).param("id", expenseId).param("userId", userId).update();

        return new VoidExpenseResponse(query.findOne(userId, expenseId), query.findOne(userId, refundId));
    }

    /** Estado mínimo del gasto para decidir una edición o anulación, con la fila bloqueada. */
    private record Current(String origin, String kind, String status, UUID cardId, UUID categoryId,
                           BigDecimal amount, String merchantRaw, UUID voidedByExpenseId, OffsetDateTime occurredAt) {
    }

    private Current lockOwned(UUID userId, UUID expenseId) {
        return jdbc.sql("""
                select origin::text as origin, kind, status, card_id, category_id, amount, merchant_raw,
                       voided_by_expense_id, occurred_at
                  from expenses
                 where id = :id and user_id = :userId
                   for update
                """).param("id", expenseId).param("userId", userId)
                .query((rs, rowNum) -> new Current(rs.getString("origin"), rs.getString("kind"),
                        rs.getString("status"), rs.getObject("card_id", UUID.class),
                        rs.getObject("category_id", UUID.class), rs.getBigDecimal("amount"),
                        rs.getString("merchant_raw"), rs.getObject("voided_by_expense_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new NotFoundException(ExpenseQueryService.NOT_FOUND));
    }

    private ExpenseResponse findByIdempotency(UUID userId, UUID key) {
        return jdbc.sql("select id from expenses where user_id = :userId and idempotency_key = :key")
                .param("userId", userId).param("key", key).query(UUID.class).optional()
                .map(id -> query.findOne(userId, id)).orElse(null);
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

}
