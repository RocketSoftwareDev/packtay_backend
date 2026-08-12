package ec.paktay.business.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.CardResponse;
import ec.paktay.business.dto.CreateCardRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardService {
    private final JdbcClient jdbc;
    private final UserAccountService users;

    public CardService(JdbcClient jdbc, UserAccountService users) { this.jdbc = jdbc; this.users = users; }

    @Transactional
    public CardResponse register(UUID userId, CreateCardRequest request) {
        users.ensureActiveUser(userId);
        String currency = request.currencyCode() == null ? "USD" : request.currencyCode();
        String bankName = jdbc.sql("select name from banks where id = :bankId and active")
                .param("bankId", request.bankId()).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("El banco seleccionado no existe o está inactivo"));
        boolean currencyExists = jdbc.sql("select exists(select 1 from currencies where code = :code and active)")
                .param("code", currency).query(Boolean.class).single();
        if (!currencyExists) throw new IllegalArgumentException("La moneda seleccionada no existe o está inactiva");

        String alias = request.alias() == null || request.alias().isBlank()
                ? bankName + " • " + request.last4() : request.alias().trim();
        UUID cardId;
        try {
            cardId = jdbc.sql("""
                    insert into cards (user_id, bank_id, alias, last4, default_currency_code)
                    values (:userId, :bankId, :alias, :last4, :currency)
                    returning id
                    """).param("userId", userId).param("bankId", request.bankId()).param("alias", alias)
                    .param("last4", request.last4()).param("currency", currency).query(UUID.class).single();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya existe una tarjeta activa con ese banco y últimos cuatro dígitos");
        }

        UUID periodId = currentPeriod(userId);
        if (request.initialBudget() != null) {
            jdbc.sql("""
                    insert into budget_allocations (user_id, period_id, scope, card_id, amount, currency_code, exchange_rate_to_usd)
                    values (:userId, :periodId, 'CARD', :cardId, :amount, :currency, 1)
                    """).param("userId", userId).param("periodId", periodId).param("cardId", cardId)
                    .param("amount", request.initialBudget()).param("currency", currency).update();
        }
        return findOne(userId, cardId, periodId);
    }

    @Transactional
    public List<CardResponse> list(UUID userId) {
        users.ensureActiveUser(userId);
        return jdbc.sql("""
                select c.id, b.id as bank_id, b.name as bank_name, c.alias, c.last4, c.default_currency_code,
                       c.status::text, ba.amount_usd as current_period_budget, c.created_at
                  from cards c
                  join banks b on b.id = c.bank_id
                  left join financial_periods fp on fp.user_id = c.user_id
                       and fp.period_month = date_trunc('month', current_date)::date
                  left join budget_allocations ba on ba.period_id = fp.id and ba.card_id = c.id and ba.scope = 'CARD'
                 where c.user_id = :userId
                 order by c.created_at desc
                """).param("userId", userId).query(this::mapCard).list();
    }

    private CardResponse findOne(UUID userId, UUID cardId, UUID periodId) {
        return jdbc.sql("""
                select c.id, b.id as bank_id, b.name as bank_name, c.alias, c.last4, c.default_currency_code,
                       c.status::text, ba.amount_usd as current_period_budget, c.created_at
                  from cards c join banks b on b.id = c.bank_id
                  left join budget_allocations ba on ba.period_id = :periodId and ba.card_id = c.id and ba.scope = 'CARD'
                 where c.user_id = :userId and c.id = :cardId
                """).param("userId", userId).param("cardId", cardId).param("periodId", periodId)
                .query(this::mapCard).single();
    }

    private UUID currentPeriod(UUID userId) {
        return jdbc.sql("""
                insert into financial_periods (user_id, period_month)
                values (:userId, date_trunc('month', current_date)::date)
                on conflict (user_id, period_month) do update set period_month = excluded.period_month
                returning id
                """).param("userId", userId).query(UUID.class).single();
    }

    private CardResponse mapCard(ResultSet rs, int rowNum) throws SQLException {
        return new CardResponse(rs.getObject("id", UUID.class), rs.getObject("bank_id", UUID.class), rs.getString("bank_name"),
                rs.getString("alias"), rs.getString("last4"), rs.getString("default_currency_code"), rs.getString("status"),
                rs.getObject("current_period_budget", BigDecimal.class), rs.getObject("created_at", OffsetDateTime.class));
    }
}
