package ec.paktay.business.service;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El límite propio de una tarjeta ("Mi límite $300") se repite cada mes.
 *
 * Cada límite vive en budget_allocations con scope CARD y un period_id. Cuando se
 * crea el período de un mes nuevo, se copia a él el último límite de cada tarjeta
 * ACTIVA tomado de un mes anterior. No pisa un límite que ya exista en ese período
 * (índice parcial budget_card_uq), así que es idempotente y seguro de llamar cada vez
 * que se resuelve el período actual.
 *
 * Tarjetas INACTIVE o DELETED no arrastran su límite: si se reactivan, el usuario
 * vuelve a poner el que quiera.
 */
final class CardLimitCarryOver {

    private static final String SQL = """
            insert into budget_allocations (user_id, period_id, scope, card_id, amount, currency_code, exchange_rate_to_usd)
            select distinct on (ba.card_id)
                   :userId, :periodId, 'CARD'::budget_scope, ba.card_id, ba.amount, ba.currency_code, ba.exchange_rate_to_usd
              from budget_allocations ba
              join financial_periods fp on fp.id = ba.period_id
              join cards c on c.id = ba.card_id
             where ba.user_id = :userId
               and ba.scope = 'CARD'
               and ba.active
               and c.status = 'ACTIVE'
               and fp.period_month < (select cur.period_month from financial_periods cur where cur.id = :periodId)
             order by ba.card_id, fp.period_month desc
            on conflict (period_id, card_id) where scope = 'CARD' do nothing
            """;

    private CardLimitCarryOver() { }

    static void apply(JdbcClient jdbc, UUID userId, UUID periodId) {
        jdbc.sql(SQL).param("userId", userId).param("periodId", periodId).update();
    }
}
