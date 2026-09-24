package ec.paktay.business.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ec.paktay.business.dto.CardResponse;
import ec.paktay.business.dto.CreateCardRequest;
import ec.paktay.business.dto.UpdateCardRequest;
import ec.paktay.business.exception.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardService {
    /** Meses sin consumos que exige la desactivación de una tarjeta. */
    public static final int QUIET_MONTHS_BEFORE_DEACTIVATION = 3;
    /** Tarjetas registradas (activas o desactivadas) que admite el plan Free. */
    public static final int FREE_PLAN_CARDS = 2;
    static final String DUPLICATE_NAME_MESSAGE =
            "Ese nombre de Wallet ya está asociado a otra de tus tarjetas activas";

    private final JdbcClient jdbc;
    private final UserAccountService users;
    private final AuditService audit;

    public CardService(JdbcClient jdbc, UserAccountService users, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    @Transactional
    public CardResponse register(UUID userId, CreateCardRequest request) {
        users.ensureActiveUser(userId);
        String currency = request.currencyCode() == null ? "USD" : request.currencyCode();
        validateOffering(request);
        boolean currencyExists = jdbc.sql("select exists(select 1 from currencies where code = :code and active)")
                .param("code", currency).query(Boolean.class).single();
        if (!currencyExists) throw new IllegalArgumentException("La moneda seleccionada no existe o está inactiva");
        // El nombre es opcional desde v0.20. Cuando llega —Postman, o un alta que
        // ya sabe qué manda Wallet— se valida igual que al asociarlo después.
        String walletName = trimToNull(request.name());
        if (walletName != null) ensureWalletNameFree(userId, walletName, null);
        ensureFreePlanRoom(userId);

        UUID cardId;
        try {
            cardId = jdbc.sql("""
                    insert into cards (user_id, bank_id, card_type, credit_brand, name, alias, last4, color_dark, color_light, default_currency_code)
                    values (:userId, :bankId, :cardType, :brand, :name, :alias, :last4, :colorDark, :colorLight, :currency)
                    returning id
                    """).param("userId", userId).param("bankId", request.bankId())
                    .param("cardType", request.cardType()).param("name", walletName, java.sql.Types.VARCHAR)
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

    /**
     * Plan Free: como máximo {@link #FREE_PLAN_CARDS} tarjetas registradas, activas o
     * desactivadas. Reactivar no suma (la tarjeta ya contaba); para una tercera hay
     * que pasar a Pro o eliminar una. Sin fila en user_subscription el usuario es
     * tester con Pro, como toda la beta.
     */
    private void ensureFreePlanRoom(UUID userId) {
        String plan = jdbc.sql("select plan from user_subscription where user_id = :userId and status = 'ACTIVE'")
                .param("userId", userId).query(String.class).optional().orElse("PRO");
        if (!"FREE".equals(plan)) return;
        int registered = jdbc.sql("select count(*) from cards where user_id = :userId and status::text <> 'DELETED'")
                .param("userId", userId).query(Integer.class).single();
        if (registered >= FREE_PLAN_CARDS) {
            throw new ConflictException("Tienes " + registered + " tarjetas registradas. Pasa al plan Pro o elimina una para agregar otra.");
        }
    }

    /**
     * Límite propio del mes actual. Con amount null se desactiva la fila del mes
     * (active = false) en vez de borrarla: CardLimitCarryOver mira la última fila de
     * cada tarjeta y así el límite quitado no reaparece el mes siguiente.
     */
    @Transactional
    public CardResponse setLimit(UUID userId, UUID cardId, BigDecimal amount) {
        users.ensureActiveUser(userId);
        if (!"ACTIVE".equals(statusOf(userId, cardId))) {
            throw new IllegalArgumentException("Sólo una tarjeta activa tiene límite mensual");
        }
        UUID periodId = currentPeriod(userId);
        if (amount == null) {
            jdbc.sql("""
                    update budget_allocations set active = false, updated_at = now()
                     where user_id = :userId and period_id = :periodId and card_id = :cardId and scope = 'CARD'
                    """).param("userId", userId).param("periodId", periodId).param("cardId", cardId).update();
        } else {
            jdbc.sql("""
                    insert into budget_allocations (user_id, period_id, scope, card_id, amount, currency_code, exchange_rate_to_usd)
                    select :userId, :periodId, 'CARD', c.id, :amount, c.default_currency_code, 1
                      from cards c where c.id = :cardId and c.user_id = :userId
                    on conflict (period_id, card_id) where scope = 'CARD' do update
                       set amount = excluded.amount, active = true, updated_at = now()
                    """).param("userId", userId).param("periodId", periodId).param("cardId", cardId)
                    .param("amount", amount).update();
        }
        audit.record(userId, "UPDATE", "card_limit", cardId, amount == null ? Map.of("removed", true) : Map.of("amount", amount));
        return findOne(userId, cardId, periodId);
    }

    /**
     * Tarjetas del usuario sin las eliminadas (DELETED). El presupuesto que devuelve
     * es el del mes actual en la zona horaria del usuario.
     */
    @Transactional
    public List<CardResponse> list(UUID userId) {
        users.ensureActiveUser(userId);
        // Resolver el período del mes arrastra los límites propios de las tarjetas
        // (CardLimitCarryOver): sin esto, el 1 del mes la lista saldría sin límite.
        UUID periodId = currentPeriod(userId);
        return jdbc.sql("""
                select c.id, b.id as bank_id, b.name as bank_name, b.logo_url as bank_logo_url,
                       c.card_type, c.credit_brand, c.name, c.alias, c.last4, c.color_dark, c.color_light, c.default_currency_code,
                       c.status::text, ba.amount_usd as current_period_budget, c.created_at
                  from cards c
                  join banks b on b.id = c.bank_id
                  left join budget_allocations ba on ba.period_id = :periodId and ba.card_id = c.id and ba.scope = 'CARD' and ba.active
                 where c.user_id = :userId and c.status::text <> 'DELETED'
                 order by (c.status = 'ACTIVE') desc, c.created_at desc
                """).param("userId", userId).param("periodId", periodId).query(this::mapCard).list();
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
        rejectDeleted(status);
        if ("INACTIVE".equals(status)) throw new IllegalArgumentException("La tarjeta ya está desactivada");
        int recent = recentExpenseCount(userId, cardId);
        if (recent > 0) {
            throw new IllegalArgumentException("La tarjeta tiene " + recent + (recent == 1 ? " consumo" : " consumos")
                    + " en los últimos " + QUIET_MONTHS_BEFORE_DEACTIVATION + " meses y no se puede desactivar");
        }
        jdbc.sql("""
                update cards set status = 'INACTIVE', deactivated_at = now(), updated_at = now()
                 where id = :cardId and user_id = :userId
                """).param("cardId", cardId).param("userId", userId).update();
        audit.record(userId, "DEACTIVATE", "card", cardId, null);
        return findOne(userId, cardId, currentPeriod(userId));
    }

    /**
     * Reactiva una tarjeta desactivada. El índice cards_active_identity_uq vuelve a
     * exigir que no exista otra tarjeta ACTIVA del usuario con el mismo nombre de
     * Wallet. Una tarjeta eliminada no se reactiva.
     */
    @Transactional
    public CardResponse activate(UUID userId, UUID cardId) {
        users.ensureActiveUser(userId);
        String status = statusOf(userId, cardId);
        rejectDeleted(status);
        if ("ACTIVE".equals(status)) throw new IllegalArgumentException("La tarjeta ya está activa");
        try {
            jdbc.sql("""
                    update cards set status = 'ACTIVE', deactivated_at = null, updated_at = now()
                     where id = :cardId and user_id = :userId
                    """).param("cardId", cardId).param("userId", userId).update();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Otra tarjeta activa tiene asociado el mismo nombre de Wallet. Quítaselo o desactívala antes de activar esta.");
        }
        audit.record(userId, "ACTIVATE", "card", cardId, null);
        return findOne(userId, cardId, currentPeriod(userId));
    }

    /**
     * Asocia a una tarjeta el nombre con el que Wallet la identifica en el atajo.
     *
     * Es la única forma de escribir {@code cards.name}: el alta ya no lo pide y
     * {@code PUT /cards/{id}} no lo toca. El usuario no escribe este texto, sólo
     * dice a qué tarjeta suya pertenece el que acaba de llegar.
     *
     * Reasignar está permitido —el móvil enseña antes el aviso de riesgo— pero
     * quitárselo a otra tarjeta no: si el nombre ya es de otra activa, contesta
     * 400 y el usuario tiene que quitárselo a esa primero. Hacerlo en silencio
     * dejaría dos tarjetas cambiadas de un solo toque.
     */
    @Transactional
    public CardResponse associateWalletName(UUID userId, UUID cardId, String walletName) {
        users.ensureActiveUser(userId);
        String status = statusOf(userId, cardId);
        rejectDeleted(status);
        if (!"ACTIVE".equals(status)) {
            throw new IllegalArgumentException("Una tarjeta desactivada no puede recibir consumos, así que no se le asocia un nombre");
        }
        String name = trimToNull(walletName);
        if (name == null) throw new IllegalArgumentException("El nombre de Wallet no puede estar vacío");
        ensureWalletNameFree(userId, name, cardId);
        try {
            jdbc.sql("update cards set name = :name, updated_at = now() where id = :cardId and user_id = :userId")
                    .param("name", name).param("cardId", cardId).param("userId", userId).update();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException(DUPLICATE_NAME_MESSAGE);
        }
        // Sin el nombre en sí: es texto que el usuario reconoce y no aporta a la auditoría.
        audit.record(userId, "LINK", "card_wallet_name", cardId, null);
        return findOne(userId, cardId, currentPeriod(userId));
    }

    /**
     * Deja la tarjeta sin nombre de Wallet.
     *
     * Hace falta para reasignar: quien se equivocó de tarjeta se lo quita a esa y
     * se lo pone a la correcta. A partir de aquí, los consumos que lleguen con ese
     * nombre vuelven a la cola sin asignar, que es exactamente lo que el aviso de
     * la app promete.
     */
    @Transactional
    public CardResponse clearWalletName(UUID userId, UUID cardId) {
        users.ensureActiveUser(userId);
        rejectDeleted(statusOf(userId, cardId));
        jdbc.sql("update cards set name = null, updated_at = now() where id = :cardId and user_id = :userId")
                .param("cardId", cardId).param("userId", userId).update();
        audit.record(userId, "UNLINK", "card_wallet_name", cardId, null);
        return findOne(userId, cardId, currentPeriod(userId));
    }

    /**
     * Elimina una tarjeta de forma lógica: pasa a DELETED, desaparece de GET /cards,
     * libera su nombre de Wallet (name = null) y no se puede reactivar. Sus gastos no
     * se tocan y siguen contando en totales e historial (ExpenseResponse.cardStatus =
     * DELETED). Misma condición que desactivar: ningún consumo en los últimos
     * {@link #QUIET_MONTHS_BEFORE_DEACTIVATION} meses. Vale desde ACTIVE o INACTIVE.
     * Los presupuestos por tarjeta (budget_allocations) se borran.
     */
    @Transactional
    public void delete(UUID userId, UUID cardId) {
        users.ensureActiveUser(userId);
        String status = statusOf(userId, cardId);
        rejectDeleted(status);
        int recent = recentExpenseCount(userId, cardId);
        if (recent > 0) {
            throw new IllegalArgumentException("La tarjeta tiene " + recent + (recent == 1 ? " consumo" : " consumos")
                    + " en los últimos " + QUIET_MONTHS_BEFORE_DEACTIVATION + " meses y no se puede eliminar");
        }
        jdbc.sql("delete from budget_allocations where user_id = :userId and card_id = :cardId")
                .param("userId", userId).param("cardId", cardId).update();
        jdbc.sql("""
                update cards set status = 'DELETED', deactivated_at = now(), name = null, updated_at = now()
                 where id = :cardId and user_id = :userId
                """).param("cardId", cardId).param("userId", userId).update();
        audit.record(userId, "DELETE", "card", cardId, Map.of("previousStatus", status));
    }

    /**
     * Consumos de la tarjeta en los últimos {@link #QUIET_MONTHS_BEFORE_DEACTIVATION}
     * meses. Sólo cuenta gastos ACTIVE de tipo EXPENSE: un gasto anulado o un
     * reembolso no demuestran que la tarjeta siga en uso.
     */
    private int recentExpenseCount(UUID userId, UUID cardId) {
        return jdbc.sql("""
                select count(*) from expenses
                 where user_id = :userId and card_id = :cardId
                   and status = 'ACTIVE' and kind = 'EXPENSE'
                   and occurred_at >= now() - make_interval(months => :months)
                """).param("userId", userId).param("cardId", cardId)
                .param("months", QUIET_MONTHS_BEFORE_DEACTIVATION).query(Integer.class).single();
    }

    private String statusOf(UUID userId, UUID cardId) {
        return jdbc.sql("select status::text from cards where id = :cardId and user_id = :userId")
                .param("cardId", cardId).param("userId", userId).query(String.class)
                .optional().orElseThrow(() -> new IllegalArgumentException("La tarjeta no existe"));
    }

    private void rejectDeleted(String status) {
        if ("DELETED".equals(status)) throw new IllegalArgumentException("La tarjeta fue eliminada");
    }

    /**
     * Misma regla que el índice cards_active_wallet_name_uq (v0.20), comprobada antes
     * de escribir para devolver un mensaje claro en vez de depender del error de la
     * base. Es **por usuario y no por banco**: el atajo busca por nombre sin saber de
     * qué banco viene el consumo.
     */
    private void ensureWalletNameFree(UUID userId, String walletName, UUID exceptCardId) {
        boolean taken = jdbc.sql("""
                select exists(select 1 from cards
                 where user_id = :userId and status = 'ACTIVE' and name is not null
                   and lower(btrim(name)) = lower(btrim(:name))
                   and (:exceptId::uuid is null or id <> :exceptId::uuid))
                """).param("userId", userId).param("name", walletName)
                .param("exceptId", exceptCardId, java.sql.Types.OTHER)
                .query(Boolean.class).single();
        if (taken) throw new IllegalArgumentException(DUPLICATE_NAME_MESSAGE);
    }

    @Transactional
    public CardResponse update(UUID userId, UUID cardId, UpdateCardRequest request) {
        users.ensureActiveUser(userId);
        rejectDeleted(statusOf(userId, cardId));
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
                  left join budget_allocations ba on ba.period_id = :periodId and ba.card_id = c.id and ba.scope = 'CARD' and ba.active
                 where c.user_id = :userId and c.id = :cardId
                """).param("userId", userId).param("cardId", cardId).param("periodId", periodId)
                .query(this::mapCard).single();
    }

    /** Período del mes actual en la zona horaria del usuario; lo crea si no existe. */
    private UUID currentPeriod(UUID userId) {
        UUID periodId = jdbc.sql("""
                insert into financial_periods (user_id, period_month)
                select u.id, date_trunc('month', now() at time zone u.timezone)::date
                  from app_users u where u.id = :userId
                on conflict (user_id, period_month) do update set period_month = excluded.period_month
                returning id
                """).param("userId", userId).query(UUID.class).single();
        CardLimitCarryOver.apply(jdbc, userId, periodId);
        return periodId;
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
