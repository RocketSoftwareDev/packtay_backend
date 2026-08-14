package ec.paktay.business.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.CardResponse;
import ec.paktay.business.dto.CreateCardRequest;
import ec.paktay.business.dto.UpdateCardRequest;
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
        validateOffering(request);
        boolean currencyExists = jdbc.sql("select exists(select 1 from currencies where code = :code and active)")
                .param("code", currency).query(Boolean.class).single();
        if (!currencyExists) throw new IllegalArgumentException("La moneda seleccionada no existe o está inactiva");

        UUID cardId;
        try {
            cardId = jdbc.sql("""
                    insert into cards (user_id, bank_id, card_type, credit_brand, name, last4, color_dark, color_light, default_currency_code)
                    values (:userId, :bankId, :cardType, :brand, :name, :last4, :colorDark, :colorLight, :currency)
                    returning id
                    """).param("userId", userId).param("bankId", request.bankId())
                    .param("cardType", request.cardType()).param("name", request.name().trim())
                    .param("brand", "CREDIT".equals(request.cardType()) ? request.creditBrand() : null)
                    .param("colorDark", request.colorDark().toUpperCase()).param("colorLight", request.colorLight().toUpperCase())
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
                select c.id, b.id as bank_id, b.name as bank_name, b.logo_url as bank_logo_url,
                       c.card_type, c.credit_brand, c.name, c.last4, c.color_dark, c.color_light, c.default_currency_code,
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

    @Transactional
    public CardResponse update(UUID userId, UUID cardId, UpdateCardRequest request) {
        users.ensureActiveUser(userId);
        jdbc.sql("select id from cards where id=:cardId and user_id=:userId")
                .param("cardId", cardId).param("userId", userId).query(UUID.class)
                .optional().orElseThrow(() -> new IllegalArgumentException("La tarjeta no existe"));
        jdbc.sql("""
                update cards set name=:name, color_dark=:colorDark, color_light=:colorLight, updated_at=now()
                 where id=:cardId and user_id=:userId
                """).param("name", request.name().trim()).param("colorDark", request.colorDark().toUpperCase())
                .param("colorLight", request.colorLight().toUpperCase()).param("cardId", cardId)
                .param("userId", userId).update();
        return findOne(userId, cardId, currentPeriod(userId));
    }

    private CardResponse findOne(UUID userId, UUID cardId, UUID periodId) {
        return jdbc.sql("""
                select c.id, b.id as bank_id, b.name as bank_name, b.logo_url as bank_logo_url,
                       c.card_type, c.credit_brand, c.name, c.last4, c.color_dark, c.color_light, c.default_currency_code,
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
                rs.getString("bank_logo_url"), rs.getString("card_type"), rs.getString("credit_brand"), rs.getString("name"), rs.getString("last4"),
                rs.getString("color_dark"), rs.getString("color_light"),
                rs.getString("default_currency_code"), rs.getString("status"),
                rs.getObject("current_period_budget", BigDecimal.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    private void validateOffering(CreateCardRequest request) {
        validateOffering(request.bankId(), request.cardType(), request.creditBrand());
    }

    private void validateOffering(UUID bankId, String cardType, String creditBrand) {
        if ("DEBIT".equals(cardType) && creditBrand != null) {
            throw new IllegalArgumentException("Las tarjetas de débito no registran franquicia o marca");
        }
        if ("CREDIT".equals(cardType) && creditBrand == null) {
            throw new IllegalArgumentException("La franquicia o marca es obligatoria para una tarjeta de crédito");
        }
        boolean allowed = jdbc.sql("""
                select exists(select 1 from bank_card_offerings o join banks b on b.id=o.bank_id
                 where o.bank_id=:bankId and o.card_type=:cardType and o.active and b.active
                   and ((:cardType='DEBIT' and o.brand is null) or o.brand=:brand))
                """).param("bankId", bankId).param("cardType", cardType)
                .param("brand", creditBrand, java.sql.Types.VARCHAR).query(Boolean.class).single();
        if (!allowed) throw new IllegalArgumentException("El banco no ofrece el tipo o marca de tarjeta seleccionados");
    }

}
