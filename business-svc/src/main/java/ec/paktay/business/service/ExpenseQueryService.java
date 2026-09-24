package ec.paktay.business.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.ExpensePageResponse;
import ec.paktay.business.dto.ExpenseResponse;
import ec.paktay.business.exception.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseQueryService {
    /** Columnas y joins que lee {@link #mapRow}. También lo usa ExpenseService. */
    static final String SELECT_EXPENSE = """
            select e.id, c.id as card_id, c.name as card_name, c.status::text as card_status,
                   uc.id as category_id, uc.name as category_name,
                   e.origin::text as origin, e.kind, e.status, e.voided_by_expense_id, e.assigned_by_rule,
                   e.amount, e.currency_code, e.merchant_raw, e.occurred_at, e.updated_at
              from expenses e
              join cards c on c.id = e.card_id
              join user_categories uc on uc.id = e.category_id
            """;

    static final String NOT_FOUND = "El gasto no existe o no pertenece al usuario";

    private final JdbcClient jdbc;
    private final UserAccountService users;

    public ExpenseQueryService(JdbcClient jdbc, UserAccountService users) { this.jdbc = jdbc; this.users = users; }

    /**
     * Historial paginado por clave (sin OFFSET).
     *
     * Sin since: todas las filas del usuario (incluidos anulados, REFUND y gastos de
     * tarjetas desactivadas o eliminadas), por occurred_at desc, id desc. from/to son
     * días del calendario del usuario, convertidos a instantes con su zona horaria.
     *
     * Con since: filas con updated_at >= since, por updated_at asc, id asc, para la
     * sincronización incremental del espejo del teléfono. Los filtros from/to/cardId/
     * categoryId se siguen aplicando si llegan.
     *
     * Se pide limit + 1 filas: si llega la fila extra hay página siguiente y
     * nextCursor apunta a la última fila entregada.
     */
    @Transactional
    public ExpensePageResponse list(UUID userId, LocalDate from, LocalDate to, UUID cardId, UUID categoryId,
                                    Integer limit, String cursor, Instant since) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la fecha final");
        }
        int pageSize = ExpenseCursor.resolveLimit(limit);
        ExpenseCursor.Mode mode = since == null ? ExpenseCursor.Mode.OCCURRED : ExpenseCursor.Mode.UPDATED;
        ExpenseCursor after = cursor == null || cursor.isBlank() ? null : ExpenseCursor.decode(cursor, mode);
        users.ensureActiveUser(userId);
        ZoneId zone = users.zoneOf(userId);

        StringBuilder sql = new StringBuilder(SELECT_EXPENSE).append(" where e.user_id = :userId");
        if (from != null) sql.append(" and e.occurred_at >= :from");
        if (to != null) sql.append(" and e.occurred_at < :toExclusive");
        if (cardId != null) sql.append(" and e.card_id = :cardId");
        if (categoryId != null) sql.append(" and e.category_id = :categoryId");
        if (mode == ExpenseCursor.Mode.UPDATED) {
            sql.append(" and e.updated_at >= :since");
            if (after != null) sql.append(" and (e.updated_at, e.id) > (:cursorAt, :cursorId)");
            sql.append(" order by e.updated_at asc, e.id asc");
        } else {
            if (after != null) sql.append(" and (e.occurred_at, e.id) < (:cursorAt, :cursorId)");
            sql.append(" order by e.occurred_at desc, e.id desc");
        }
        sql.append(" limit :fetch");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("userId", userId);
        if (from != null) statement.param("from", from.atStartOfDay(zone).toOffsetDateTime());
        if (to != null) statement.param("toExclusive", to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        if (cardId != null) statement.param("cardId", cardId);
        if (categoryId != null) statement.param("categoryId", categoryId);
        if (since != null) statement.param("since", utc(since));
        if (after != null) statement.param("cursorAt", utc(after.position())).param("cursorId", after.id());
        statement.param("fetch", pageSize + 1);

        List<ExpenseResponse> rows = statement.query(ExpenseQueryService::mapRow).list();
        if (rows.size() <= pageSize) return new ExpensePageResponse(rows, null);
        List<ExpenseResponse> page = List.copyOf(rows.subList(0, pageSize));
        ExpenseResponse last = page.get(page.size() - 1);
        Instant position = (mode == ExpenseCursor.Mode.UPDATED ? last.updatedAt() : last.occurredAt()).toInstant();
        return new ExpensePageResponse(page, new ExpenseCursor(mode, position, last.id()).encode());
    }

    /** Detalle de un gasto propio; 404 si no existe o es de otro usuario. */
    @Transactional
    public ExpenseResponse get(UUID userId, UUID expenseId) {
        users.ensureActiveUser(userId);
        return findOne(userId, expenseId);
    }

    /** Lee un gasto del usuario con el único builder de ExpenseResponse. */
    ExpenseResponse findOne(UUID userId, UUID expenseId) {
        return jdbc.sql(SELECT_EXPENSE + " where e.user_id = :userId and e.id = :expenseId")
                .param("userId", userId).param("expenseId", expenseId)
                .query(ExpenseQueryService::mapRow).optional()
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }

    static ExpenseResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExpenseResponse(rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class),
                rs.getString("card_name"), rs.getString("card_status"),
                rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                rs.getString("origin"), rs.getString("kind"), rs.getString("status"),
                rs.getObject("voided_by_expense_id", UUID.class), rs.getBoolean("assigned_by_rule"),
                rs.getObject("amount", BigDecimal.class), rs.getString("currency_code"),
                rs.getString("merchant_raw"), rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
