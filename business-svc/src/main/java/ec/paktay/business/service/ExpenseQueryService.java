package ec.paktay.business.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.ExpenseResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseQueryService {
    /** Columnas y joins que lee {@link #mapRow}. También lo usa ExpenseService. */
    static final String SELECT_EXPENSE = """
            select e.id, c.id as card_id, c.name as card_name, c.status::text as card_status,
                   uc.id as category_id, uc.name as category_name,
                   e.origin::text, e.amount, e.currency_code, e.merchant_raw, e.occurred_at,
                   e.kind, e.status, e.assigned_by_rule
              from expenses e
              join cards c on c.id = e.card_id
              join user_categories uc on uc.id = e.category_id
            """;

    private final JdbcClient jdbc;
    private final UserAccountService users;

    public ExpenseQueryService(JdbcClient jdbc, UserAccountService users) { this.jdbc = jdbc; this.users = users; }

    /**
     * Historial de gastos, incluidos los de tarjetas desactivadas o eliminadas y los
     * anulados (el campo status lo indica). from/to son días del calendario del
     * usuario: se convierten a instantes con su zona horaria (app_users.timezone).
     */
    @Transactional
    public List<ExpenseResponse> list(UUID userId, LocalDate from, LocalDate to, UUID cardId, UUID categoryId) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la fecha final");
        }
        users.ensureActiveUser(userId);
        ZoneId zone = users.zoneOf(userId);
        StringBuilder sql = new StringBuilder(SELECT_EXPENSE).append(" where e.user_id = :userId");
        if (from != null) sql.append(" and e.occurred_at >= :from");
        if (to != null) sql.append(" and e.occurred_at < :toExclusive");
        if (cardId != null) sql.append(" and e.card_id = :cardId");
        if (categoryId != null) sql.append(" and e.category_id = :categoryId");
        sql.append(" order by e.occurred_at desc, e.created_at desc");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("userId", userId);
        if (from != null) statement.param("from", from.atStartOfDay(zone).toOffsetDateTime());
        if (to != null) statement.param("toExclusive", to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        if (cardId != null) statement.param("cardId", cardId);
        if (categoryId != null) statement.param("categoryId", categoryId);
        return statement.query(ExpenseQueryService::mapRow).list();
    }

    static ExpenseResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExpenseResponse(rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class),
                rs.getString("card_name"), rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                rs.getString("origin"), rs.getObject("amount", BigDecimal.class), rs.getString("currency_code"),
                rs.getString("merchant_raw"), rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("card_status"), rs.getString("kind"), rs.getString("status"),
                rs.getBoolean("assigned_by_rule"));
    }
}
