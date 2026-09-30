package ec.paktay.business.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ec.paktay.business.dto.ConfirmOccurrenceRequest;
import ec.paktay.business.dto.ExpenseResponse;
import ec.paktay.business.dto.RecurringListResponse;
import ec.paktay.business.dto.RecurringOccurrenceResponse;
import ec.paktay.business.dto.RecurringPaymentRequest;
import ec.paktay.business.dto.RecurringPaymentResponse;
import ec.paktay.business.exception.ConflictException;
import ec.paktay.business.exception.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pagos recurrentes (día 8a). Ver V12 y {@link RecurrenceSchedule}.
 *
 * - El usuario los crea a mano; la app nunca adivina recurrentes.
 * - El servidor deja cada cobro como PENDING (este servicio al leer y {@link RecurringJob}
 *   cada media hora), en la zona horaria del usuario. No se recuperan más de 3 meses.
 * - Confirmar crea el gasto; «Este mes no se cobró» lo descarta; «Cancelar pago
 *   recurrente» cancela la serie y ese cobro no se guarda.
 * - Plan Gratis: 2 recurrentes activos (los pausados no cuentan).
 */
@Service
public class RecurringPaymentService {
    public static final int FREE_RECURRING = 2;
    static final int MAX_BACKFILL_MONTHS = 3;
    static final String NOT_FOUND = "El pago recurrente no existe o no pertenece al usuario";
    static final String OCCURRENCE_NOT_FOUND = "El cobro no existe, no es del usuario o ya se resolvió";

    private static final String SELECT = """
            select r.id, r.name, r.amount, r.currency_code, r.card_id,
                   coalesce(nullif(btrim(c.alias), ''), b.name) as card_name, c.status::text as card_status,
                   r.category_id, coalesce(nullif(btrim(uc.alias), ''), uc.name) as category_name,
                   r.frequency, r.day_rule, r.day_of_month, r.month_of_year, r.end_month, r.starts_on, r.status
              from recurring_payments r
              join cards c on c.id = r.card_id
              join banks b on b.id = c.bank_id
              join user_categories uc on uc.id = r.category_id
            """;

    private final JdbcClient jdbc;
    private final UserAccountService users;
    private final PlanService plans;
    private final ExpenseService expenses;

    public RecurringPaymentService(JdbcClient jdbc, UserAccountService users, PlanService plans, ExpenseService expenses) {
        this.jdbc = jdbc;
        this.users = users;
        this.plans = plans;
        this.expenses = expenses;
    }

    record Row(UUID id, String name, BigDecimal amount, String currency, UUID cardId, String cardName, String cardStatus,
               UUID categoryId, String categoryName, String frequency, String dayRule, Integer dayOfMonth,
               Integer monthOfYear, LocalDate endMonth, LocalDate startsOn, String status) {
        RecurrenceSchedule.Rule rule() {
            return new RecurrenceSchedule.Rule(frequency, dayRule, dayOfMonth, monthOfYear, endMonth);
        }
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBigDecimal("amount"),
                rs.getString("currency_code").trim(), rs.getObject("card_id", UUID.class), rs.getString("card_name"),
                rs.getString("card_status"), rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                rs.getString("frequency"), rs.getString("day_rule"), (Integer) rs.getObject("day_of_month", Integer.class),
                (Integer) rs.getObject("month_of_year", Integer.class), rs.getObject("end_month", LocalDate.class),
                rs.getObject("starts_on", LocalDate.class), rs.getString("status"));
    }

    // ---------------------------------------------------------------- lectura

    @Transactional
    public RecurringListResponse list(UUID userId) {
        users.ensureActiveUser(userId);
        generateDue(userId);
        LocalDate today = LocalDate.now(users.zoneOf(userId));
        List<RecurringPaymentResponse> items = rows(userId).stream()
                .filter(r -> !"CANCELLED".equals(r.status()))
                .map(r -> response(r, today))
                .sorted(Comparator.comparing((RecurringPaymentResponse r) -> !"ACTIVE".equals(r.status()))
                        .thenComparing(r -> r.nextDueDate() == null ? LocalDate.MAX : r.nextDueDate())
                        .thenComparing(RecurringPaymentResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        BigDecimal total = items.stream().filter(r -> "ACTIVE".equals(r.status()))
                .map(RecurringPaymentResponse::monthlyEquivalent).reduce(BigDecimal.ZERO, BigDecimal::add);
        int active = (int) items.stream().filter(r -> "ACTIVE".equals(r.status())).count();
        return new RecurringListResponse(items, total, active);
    }

    @Transactional
    public List<RecurringOccurrenceResponse> pending(UUID userId) {
        users.ensureActiveUser(userId);
        generateDue(userId);
        return jdbc.sql("""
                select o.id, o.recurring_payment_id, r.name, o.due_date, o.expected_amount, r.currency_code,
                       r.card_id, coalesce(nullif(btrim(c.alias), ''), b.name) as card_name, c.status::text as card_status,
                       r.category_id, coalesce(nullif(btrim(uc.alias), ''), uc.name) as category_name, o.status,
                       r.frequency, r.day_rule, r.day_of_month, r.month_of_year
                  from recurring_occurrences o
                  join recurring_payments r on r.id = o.recurring_payment_id
                  join cards c on c.id = r.card_id
                  join banks b on b.id = c.bank_id
                  join user_categories uc on uc.id = r.category_id
                 where o.user_id = :userId and o.status = 'PENDING'
                 order by o.due_date, r.name
                """).param("userId", userId).query(this::occurrence).list();
    }

    // ---------------------------------------------------------------- alta y edición

    @Transactional
    public RecurringPaymentResponse create(UUID userId, RecurringPaymentRequest request) {
        users.ensureActiveUser(userId);
        validate(userId, request);
        ensureFreeRoom(userId, null);
        ZoneId zone = users.zoneOf(userId);
        LocalDate today = LocalDate.now(zone);
        UUID id = jdbc.sql("""
                insert into recurring_payments (user_id, name, amount, card_id, category_id, frequency, day_rule,
                    day_of_month, month_of_year, end_month, starts_on)
                values (:userId, :name, :amount, :cardId, :categoryId, :frequency, :dayRule, :day, :month, :endMonth, :startsOn)
                returning id
                """).param("userId", userId).param("name", request.name().trim()).param("amount", request.amount())
                .param("cardId", request.cardId()).param("categoryId", request.categoryId())
                .param("frequency", request.frequency()).param("dayRule", request.dayRule())
                .param("day", dayOf(request), java.sql.Types.SMALLINT)
                .param("month", monthOf(request), java.sql.Types.SMALLINT)
                .param("endMonth", endMonthOf(request), java.sql.Types.DATE)
                .param("startsOn", request.startsTomorrow() ? today.plusDays(1) : today)
                .query(UUID.class).single();
        generateDue(userId);
        return response(one(userId, id), today);
    }

    /** Los cambios valen desde el próximo cobro: un cobro ya pendiente conserva su monto. */
    @Transactional
    public RecurringPaymentResponse update(UUID userId, UUID id, RecurringPaymentRequest request) {
        users.ensureActiveUser(userId);
        Row current = one(userId, id);
        if ("CANCELLED".equals(current.status())) throw new ConflictException("Un pago recurrente cancelado no se edita");
        validate(userId, request);
        jdbc.sql("""
                update recurring_payments set name = :name, amount = :amount, card_id = :cardId, category_id = :categoryId,
                       frequency = :frequency, day_rule = :dayRule, day_of_month = :day, month_of_year = :month,
                       end_month = :endMonth, updated_at = now()
                 where id = :id and user_id = :userId
                """).param("name", request.name().trim()).param("amount", request.amount())
                .param("cardId", request.cardId()).param("categoryId", request.categoryId())
                .param("frequency", request.frequency()).param("dayRule", request.dayRule())
                .param("day", dayOf(request), java.sql.Types.SMALLINT)
                .param("month", monthOf(request), java.sql.Types.SMALLINT)
                .param("endMonth", endMonthOf(request), java.sql.Types.DATE)
                .param("id", id).param("userId", userId).update();
        return response(one(userId, id), LocalDate.now(users.zoneOf(userId)));
    }

    @Transactional
    public RecurringPaymentResponse pause(UUID userId, UUID id) {
        users.ensureActiveUser(userId);
        Row current = one(userId, id);
        if (!"ACTIVE".equals(current.status())) throw new ConflictException("Sólo se pausa un pago recurrente activo");
        setStatus(userId, id, "PAUSED", null);
        return response(one(userId, id), LocalDate.now(users.zoneOf(userId)));
    }

    /** Reanudar no recupera los meses pausados: el próximo cobro es desde hoy. */
    @Transactional
    public RecurringPaymentResponse resume(UUID userId, UUID id) {
        users.ensureActiveUser(userId);
        Row current = one(userId, id);
        if (!"PAUSED".equals(current.status())) throw new ConflictException("Sólo se reanuda un pago recurrente pausado");
        ensureFreeRoom(userId, id);
        LocalDate today = LocalDate.now(users.zoneOf(userId));
        setStatus(userId, id, "ACTIVE", today);
        generateDue(userId);
        return response(one(userId, id), today);
    }

    /** Cancela la serie. Los gastos ya registrados no cambian; los cobros pendientes se cancelan. */
    @Transactional
    public void cancel(UUID userId, UUID id) {
        users.ensureActiveUser(userId);
        one(userId, id);
        jdbc.sql("""
                update recurring_payments set status = 'CANCELLED', cancelled_at = coalesce(cancelled_at, now()), updated_at = now()
                 where id = :id and user_id = :userId
                """).param("id", id).param("userId", userId).update();
        jdbc.sql("""
                update recurring_occurrences set status = 'CANCELLED', resolved_at = now()
                 where recurring_payment_id = :id and user_id = :userId and status = 'PENDING'
                """).param("id", id).param("userId", userId).update();
    }

    // ---------------------------------------------------------------- cobros

    @Transactional
    public ExpenseResponse confirm(UUID userId, UUID occurrenceId, ConfirmOccurrenceRequest request) {
        users.ensureActiveUser(userId);
        PendingRow pending = lockPending(userId, occurrenceId);
        Row recurring = one(userId, pending.recurringId());
        BigDecimal amount = request == null || request.amount() == null ? pending.expected() : request.amount();
        if (request != null && request.updateExpected() && amount.compareTo(recurring.amount()) != 0) {
            jdbc.sql("update recurring_payments set amount = :amount, updated_at = now() where id = :id and user_id = :userId")
                    .param("amount", amount).param("id", recurring.id()).param("userId", userId).update();
        }
        ZoneId zone = users.zoneOf(userId);
        ExpenseResponse expense = expenses.createFromRecurring(userId, recurring.id(), occurrenceId, recurring.cardId(),
                recurring.categoryId(), amount, recurring.currency(), recurring.name(),
                pending.dueDate().atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime());
        jdbc.sql("""
                update recurring_occurrences set status = 'CONFIRMED', expense_id = :expenseId, resolved_at = now()
                 where id = :id and user_id = :userId
                """).param("expenseId", expense.id()).param("id", occurrenceId).param("userId", userId).update();
        return expense;
    }

    /** «Este mes no se cobró»: descarta sólo este cobro. */
    @Transactional
    public void skip(UUID userId, UUID occurrenceId) {
        users.ensureActiveUser(userId);
        lockPending(userId, occurrenceId);
        resolve(userId, occurrenceId, "SKIPPED");
    }

    /** «Cancelar pago recurrente» desde el cobro: el cobro no se guarda y la serie termina. */
    @Transactional
    public void cancelSeries(UUID userId, UUID occurrenceId) {
        users.ensureActiveUser(userId);
        PendingRow pending = lockPending(userId, occurrenceId);
        resolve(userId, occurrenceId, "CANCELLED");
        cancel(userId, pending.recurringId());
    }

    // ---------------------------------------------------------------- generación y comprometido

    /**
     * Deja PENDING los cobros que ya tocan (hasta hoy, en la zona del usuario) de los
     * recurrentes activos. Idempotente por el índice único (recurrente, fecha).
     */
    @Transactional
    public int generateDue(UUID userId) {
        LocalDate today = LocalDate.now(users.zoneOf(userId));
        int created = 0;
        for (Row r : rows(userId)) {
            if (!"ACTIVE".equals(r.status())) continue;
            LocalDate from = r.startsOn().isAfter(today.minusMonths(MAX_BACKFILL_MONTHS))
                    ? r.startsOn() : today.minusMonths(MAX_BACKFILL_MONTHS);
            for (LocalDate due : RecurrenceSchedule.dueDatesBetween(r.rule(), from, today)) {
                created += jdbc.sql("""
                        insert into recurring_occurrences (recurring_payment_id, user_id, due_date, expected_amount)
                        values (:id, :userId, :due, :amount)
                        on conflict (recurring_payment_id, due_date) do nothing
                        """).param("id", r.id()).param("userId", userId).param("due", due).param("amount", r.amount()).update();
            }
        }
        return created;
    }

    /** Comprometido del mes actual: cobros pendientes del mes + los que faltan (desde hoy) de los activos. */
    public record Committed(BigDecimal total, Map<UUID, BigDecimal> byCategory) { }

    public Committed committed(UUID userId, String currency) {
        ZoneId zone = users.zoneOf(userId);
        LocalDate today = LocalDate.now(zone);
        YearMonth month = YearMonth.from(today);
        Map<UUID, BigDecimal> byCategory = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        List<Object[]> pendingRows = jdbc.sql("""
                select r.category_id, o.expected_amount, o.recurring_payment_id, o.due_date
                  from recurring_occurrences o join recurring_payments r on r.id = o.recurring_payment_id
                 where o.user_id = :userId and o.status = 'PENDING' and r.currency_code = :currency
                   and o.due_date >= :first and o.due_date <= :last
                """).param("userId", userId).param("currency", currency)
                .param("first", month.atDay(1)).param("last", month.atEndOfMonth())
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getBigDecimal(2),
                        rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class)}).list();
        Set<String> generated = new java.util.HashSet<>(jdbc.sql("""
                select recurring_payment_id::text || '|' || due_date::text from recurring_occurrences
                 where user_id = :userId and due_date >= :first and due_date <= :last
                """).param("userId", userId).param("first", month.atDay(1)).param("last", month.atEndOfMonth())
                .query(String.class).list());
        for (Object[] p : pendingRows) {
            total = total.add((BigDecimal) p[1]);
            byCategory.merge((UUID) p[0], (BigDecimal) p[1], BigDecimal::add);
        }
        for (Row r : rows(userId)) {
            if (!"ACTIVE".equals(r.status()) || !currency.equals(r.currency())) continue;
            LocalDate from = r.startsOn().isAfter(today) ? r.startsOn() : today;
            for (LocalDate due : RecurrenceSchedule.dueDatesBetween(r.rule(), from, month.atEndOfMonth())) {
                if (generated.contains(r.id() + "|" + due)) continue;
                total = total.add(r.amount());
                byCategory.merge(r.categoryId(), r.amount(), BigDecimal::add);
            }
        }
        return new Committed(total, byCategory);
    }

    public int activeCount(UUID userId) {
        return jdbc.sql("select count(*) from recurring_payments where user_id = :userId and status = 'ACTIVE'")
                .param("userId", userId).query(Integer.class).single();
    }

    /** Recurrentes activos con cobro en esa fecha (para el aviso de la víspera). */
    public List<Row> dueOn(UUID userId, LocalDate date) {
        List<Row> due = new ArrayList<>();
        for (Row r : rows(userId)) {
            if (!"ACTIVE".equals(r.status()) || r.startsOn().isAfter(date)) continue;
            if (date.equals(RecurrenceSchedule.dueIn(r.rule(), YearMonth.from(date)))) due.add(r);
        }
        return due;
    }

    // ---------------------------------------------------------------- apoyo

    private void validate(UUID userId, RecurringPaymentRequest request) {
        boolean card = jdbc.sql("select exists(select 1 from cards where id = :id and user_id = :userId and status = 'ACTIVE')")
                .param("id", request.cardId()).param("userId", userId).query(Boolean.class).single();
        if (!card) throw new IllegalArgumentException("La tarjeta no existe, no es tuya o no está activa");
        boolean category = jdbc.sql("select exists(select 1 from user_categories where id = :id and user_id = :userId and active)")
                .param("id", request.categoryId()).param("userId", userId).query(Boolean.class).single();
        if (!category) throw new IllegalArgumentException("La categoría no existe, no es tuya o está inactiva");
        if ("DAY".equals(request.dayRule()) && request.dayOfMonth() == null) {
            throw new IllegalArgumentException("Con «Elegir día» hay que indicar el día (1 a 31)");
        }
        if ("YEARLY".equals(request.frequency()) && request.monthOfYear() == null) {
            throw new IllegalArgumentException("Un pago anual necesita el mes");
        }
        LocalDate end = endMonthOf(request);
        if (end != null && YearMonth.from(end).isBefore(YearMonth.now(users.zoneOf(userId)))) {
            throw new IllegalArgumentException("La fecha de fin no puede ser un mes pasado");
        }
    }

    private void ensureFreeRoom(UUID userId, UUID except) {
        if (!plans.isFree(userId)) return;
        int active = jdbc.sql("""
                select count(*) from recurring_payments
                 where user_id = :userId and status = 'ACTIVE' and (:except::uuid is null or id <> :except::uuid)
                """).param("userId", userId).param("except", except, java.sql.Types.OTHER).query(Integer.class).single();
        if (active >= FREE_RECURRING) {
            throw new ConflictException("Tienes " + FREE_RECURRING + " recurrentes en el plan Gratis. Pausa o cancela uno, o pasa al plan Pro.");
        }
    }

    private static Integer dayOf(RecurringPaymentRequest r) {
        return "DAY".equals(r.dayRule()) ? r.dayOfMonth() : null;
    }

    private static Integer monthOf(RecurringPaymentRequest r) {
        return "YEARLY".equals(r.frequency()) ? r.monthOfYear() : null;
    }

    private static LocalDate endMonthOf(RecurringPaymentRequest r) {
        return r.endMonth() == null ? null : YearMonth.parse(r.endMonth()).atDay(1);
    }

    private List<Row> rows(UUID userId) {
        return jdbc.sql(SELECT + " where r.user_id = :userId").param("userId", userId).query(RecurringPaymentService::map).list();
    }

    private Row one(UUID userId, UUID id) {
        return jdbc.sql(SELECT + " where r.user_id = :userId and r.id = :id").param("userId", userId).param("id", id)
                .query(RecurringPaymentService::map).optional().orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }

    private void setStatus(UUID userId, UUID id, String status, LocalDate startsOn) {
        jdbc.sql("""
                update recurring_payments set status = :status, starts_on = coalesce(:startsOn, starts_on), updated_at = now()
                 where id = :id and user_id = :userId
                """).param("status", status).param("startsOn", startsOn, java.sql.Types.DATE)
                .param("id", id).param("userId", userId).update();
    }

    private record PendingRow(UUID recurringId, LocalDate dueDate, BigDecimal expected) { }

    private PendingRow lockPending(UUID userId, UUID occurrenceId) {
        return jdbc.sql("""
                select recurring_payment_id, due_date, expected_amount from recurring_occurrences
                 where id = :id and user_id = :userId and status = 'PENDING'
                   for update
                """).param("id", occurrenceId).param("userId", userId)
                .query((rs, n) -> new PendingRow(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3)))
                .optional().orElseThrow(() -> new NotFoundException(OCCURRENCE_NOT_FOUND));
    }

    private void resolve(UUID userId, UUID occurrenceId, String status) {
        jdbc.sql("update recurring_occurrences set status = :status, resolved_at = now() where id = :id and user_id = :userId")
                .param("status", status).param("id", occurrenceId).param("userId", userId).update();
    }

    private RecurringPaymentResponse response(Row r, LocalDate today) {
        LocalDate from = r.startsOn().isAfter(today) ? r.startsOn() : today;
        LocalDate next = "ACTIVE".equals(r.status()) ? RecurrenceSchedule.nextDue(r.rule(), from) : null;
        // Si el cobro de esa fecha ya se confirmó u omitió, el próximo es el siguiente.
        if (next != null && resolvedOn(r.id(), next)) {
            next = RecurrenceSchedule.nextDue(r.rule(), next.plusDays(1));
        }
        return new RecurringPaymentResponse(r.id(), r.name(), r.amount(), r.currency(), r.cardId(), r.cardName(),
                r.cardStatus(), r.categoryId(), r.categoryName(), r.frequency(), r.dayRule(), r.dayOfMonth(),
                r.monthOfYear(), r.endMonth() == null ? null : YearMonth.from(r.endMonth()).toString(), r.status(), next,
                RecurrenceSchedule.monthlyEquivalent(r.rule(), r.amount()));
    }

    private boolean resolvedOn(UUID recurringId, LocalDate due) {
        return jdbc.sql("""
                select exists(select 1 from recurring_occurrences
                               where recurring_payment_id = :id and due_date = :due and status <> 'PENDING')
                """).param("id", recurringId).param("due", due).query(Boolean.class).single();
    }

    private RecurringOccurrenceResponse occurrence(ResultSet rs, int n) throws SQLException {
        return new RecurringOccurrenceResponse(rs.getObject("id", UUID.class), rs.getObject("recurring_payment_id", UUID.class),
                rs.getString("name"), rs.getObject("due_date", LocalDate.class), rs.getBigDecimal("expected_amount"),
                rs.getString("currency_code").trim(), rs.getObject("card_id", UUID.class), rs.getString("card_name"),
                rs.getString("card_status"), rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                rs.getString("status"), rs.getString("frequency"), rs.getString("day_rule"),
                (Integer) rs.getObject("day_of_month", Integer.class), (Integer) rs.getObject("month_of_year", Integer.class));
    }
}
