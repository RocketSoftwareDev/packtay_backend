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
    /** Meses sin consumos que exige la desactivación de una tarjeta. */
    public static final int QUIET_MONTHS_BEFORE_DEACTIVATION = 3;
    static final String DUPLICATE_NAME_MESSAGE = "Ya tienes una tarjeta activa con ese nombre en este banco";

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
        ensureNameFreeInBank(userId, request.bankId(), request.name());

        UUID cardId;
        try {
            cardId = jdbc.sql("""
                    insert into cards (user_id, bank_id, card_type, credit_brand, name, alias, last4, color_dark, color_light, default_currency_code)
                    values (:userId, :bankId, :cardType, :brand, :name, :alias, :last4, :colorDark, :colorLight, :currency)
                    returning id
                    """).param("userId", userId).param("bankId", request.bankId())
                    .param("cardType", request.cardType()).param("name", request.name().trim())
                    .param("alias", trimToNull(request.alias()), java.sql.Types.VARCHAR)
                    .param("brand", "CREDIT".equals(request.cardType()) ? request.creditBrand() : null)
                    .param("colorDark", request.colorDark().toUpperCase()).param("colorLight", request.colorLight().toUpperCase())
                    .param("last4", request.last4()).param("currency", currency).query(UUID.class).single();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException(DUPLICATE_NAME_MESSAGE);
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
                       c.card_type, c.credit_brand, c.name, c.alias, c.last4, c.color_dark, c.color_light, c.default_currency_code,
                       c.status::text, ba.amount_usd as current_period_budget, c.created_at
                  from cards c
                  join banks b on b.id = c.bank_id
                  left join financial_periods fp on fp.user_id = c.user_id
                       and fp.period_month = date_trunc('month', current_date)::date
                  left join budget_allocations ba on ba.period_id = fp.id and ba.card_id = c.id and ba.scope = 'CARD'
                 where c.user_id = :userId
                 order by (c.status = 'ACTIVE') desc, c.created_at desc
                """).param("userId", userId).query(this::mapCard).list();
    }

    /**
     * Desactiva una tarjeta. Sólo se permite si no registró consumos en los últimos
     * {@link #QUIET_MONTHS_BEFORE_DEACTIVATION} meses: una tarjeta que sigue en uso no
     * debe desaparecer de los selectores. La tarjeta desactivada conserva su historial,
     * deja de aceptar gastos (trigger validate_expense_ownership) y libera su nombre.
     */
    @Transactional
    public CardResponse deactivate(UUID userId, UUID cardId) {
        users.ensureActiveUser(userId);
        String status = statusOf(userId, cardId);
        if ("INACTIVE".equals(status)) throw new IllegalArgumentException("La tarjeta ya está desactivada");
        int recent = jdbc.sql("""
                select count(*) from expenses
                 where user_id = :userId and card_id = :cardId
                   and occurred_at >= now() - make_interval(months => :months)
                """).param("userId", userId).param("cardId", cardId)
                .param("months", QUIET_MONTHS_BEFORE_DEACTIVATION).query(Integer.class).single();
        if (recent > 0) {
            throw new IllegalArgumentException("La tarjeta tiene " + recent + (recent == 1 ? " consumo" : " consumos")
                    + " en los últimos " + QUIET_MONTHS_BEFORE_DEACTIVATION + " meses y no se puede desactivar");
        }
        jdbc.sql("""
                update cards set status = 'INACTIVE', deactivated_at = now(), updated_at = now()
                 where id = :cardId and user_id = :userId
                """).param("cardId", cardId).param("userId", userId).update();
        return findOne(userId, cardId, currentPeriod(userId));
    }

    /**
     * Reactiva una tarjeta desactivada. El índice cards_active_identity_uq vuelve a
     * exigir que no exista otra tarjeta ACTIVA con el mismo nombre en el mismo banco.
     */
    @Transactional
    public CardResponse activate(UUID userId, UUID cardId) {
        users.ensureActiveUser(userId);
        String status = statusOf(userId, cardId);
        if ("ACTIVE".equals(status)) throw new IllegalArgumentException("La tarjeta ya está activa");
        try {
            jdbc.sql("""
                    update cards set status = 'ACTIVE', deactivated_at = null, updated_at = now()
                     where id = :cardId and user_id = :userId
                    """).param("cardId", cardId).param("userId", userId).update();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Ya tienes otra tarjeta activa con ese nombre en este banco. Desactívala o elimínala antes de activar esta.");
        }
        return findOne(userId, cardId, currentPeriod(userId));
    }

    /**
     * Elimina una tarjeta de forma definitiva. Sólo si no tiene ningún consumo en todo
     * el historial (expenses no admite borrado, así que una tarjeta con gastos sólo se
     * desactiva). Los presupuestos e ingresos ligados a la tarjeta se borran con ella
     * y los movimientos pendientes que la sugerían quedan sin sugerencia.
     */
    @Transactional
    public void delete(UUID userId, UUID cardId) {
        users.ensureActiveUser(userId);
        statusOf(userId, cardId);
        int total = jdbc.sql("select count(*) from expenses where user_id = :userId and card_id = :cardId")
                .param("userId", userId).param("cardId", cardId).query(Integer.class).single();
        if (total > 0) {
            throw new IllegalArgumentException("La tarjeta tiene " + total + (total == 1 ? " consumo" : " consumos")
                    + " registrados y no se puede eliminar. Desactívala en su lugar.");
        }
        jdbc.sql("delete from budget_allocations where user_id = :userId and card_id = :cardId")
                .param("userId", userId).param("cardId", cardId).update();
        jdbc.sql("delete from monthly_incomes where user_id = :userId and card_id = :cardId")
                .param("userId", userId).param("cardId", cardId).update();
        jdbc.sql("update pending_movements set suggested_card_id = null where user_id = :userId and suggested_card_id = :cardId")
                .param("userId", userId).param("cardId", cardId).update();
        try {
            jdbc.sql("delete from cards where id = :cardId and user_id = :userId")
                    .param("cardId", cardId).param("userId", userId).update();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("La tarjeta tiene información asociada y no se puede eliminar. Desactívala en su lugar.");
        }
    }

    private String statusOf(UUID userId, UUID cardId) {
        return jdbc.sql("select status::text from cards where id = :cardId and user_id = :userId")
                .param("cardId", cardId).param("userId", userId).query(String.class)
                .optional().orElseThrow(() -> new IllegalArgumentException("La tarjeta no existe"));
    }

    /**
     * Misma regla que el índice cards_active_identity_uq (v0.19), comprobada antes del
     * insert para devolver un mensaje claro en vez de depender del error de la base.
     */
    private void ensureNameFreeInBank(UUID userId, UUID bankId, String name) {
        boolean taken = jdbc.sql("""
                select exists(select 1 from cards
                 where user_id = :userId and bank_id = :bankId
                   and lower(btrim(name)) = lower(btrim(:name)) and status = 'ACTIVE')
                """).param("userId", userId).param("bankId", bankId).param("name", name)
                .query(Boolean.class).single();
        if (taken) throw new IllegalArgumentException(DUPLICATE_NAME_MESSAGE);
    }

    @Transactional
    public CardResponse update(UUID userId, UUID cardId, UpdateCardRequest request) {
        users.ensureActiveUser(userId);
        jdbc.sql("select id from cards where id=:cardId and user_id=:userId")
                .param("cardId", cardId).param("userId", userId).query(UUID.class)
                .optional().orElseThrow(() -> new IllegalArgumentException("La tarjeta no existe"));
        jdbc.sql("""
                update cards set alias=:alias, color_dark=:colorDark, color_light=:colorLight, updated_at=now()
                 where id=:cardId and user_id=:userId
                """).param("alias", trimToNull(request.alias()), java.sql.Types.VARCHAR).param("colorDark", request.colorDark().toUpperCase())
                .param("colorLight", request.colorLight().toUpperCase()).param("cardId", cardId)
                .param("userId", userId).update();
        return findOne(userId, cardId, currentPeriod(userId));
    }

    private CardResponse findOne(UUID userId, UUID cardId, UUID periodId) {
        return jdbc.sql("""
                select c.id, b.id as bank_id, b.name as bank_name, b.logo_url as bank_logo_url,
                       c.card_type, c.credit_brand, c.name, c.alias, c.last4, c.color_dark, c.color_light, c.default_currency_code,
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
                rs.getString("bank_logo_url"), rs.getString("card_type"), rs.getString("credit_brand"), rs.getString("name"), rs.getString("alias"), rs.getString("last4"),
                rs.getString("color_dark"), rs.getString("color_light"),
                rs.getString("default_currency_code"), rs.getString("status"),
                rs.getObject("current_period_budget", BigDecimal.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    private void validateOffering(CreateCardRequest request) {
        validateOffering(request.bankId(), request.cardType(), request.creditBrand());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
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
