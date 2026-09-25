package ec.paktay.business.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import ec.paktay.business.dto.BudgetResponse;
import ec.paktay.business.dto.CategoryBudgetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Avisos de presupuesto (día 7): al llegar al 90 % y al 100 %, por categoría y por
 * «Mi límite» de cada tarjeta.
 *
 * Reglas del dueño:
 * - Una vez por umbral, por destino y por mes (índice único de notification_log).
 * - Si el mismo gasto cruza el 90 % y el 100 % a la vez, sólo llega el de 100 %
 *   (y el de 90 % se da por enviado para que no llegue después).
 * - Sólo cuenta el mes actual: un gasto manual de hace días del mes anterior no avisa.
 *
 * Se decide dentro de la transacción del gasto (la fila de notification_log se
 * escribe con él) y se envía después del commit: si el gasto se revierte, no sale
 * ningún aviso.
 */
@Service
public class BudgetAlertService {
    private static final Logger log = LoggerFactory.getLogger(BudgetAlertService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Locale ES = Locale.forLanguageTag("es-EC");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d 'de' MMMM", ES);

    private final JdbcClient jdbc;
    private final BudgetService budgets;
    private final DeviceService devices;
    private final PushSender push;
    private final UserAccountService users;

    public BudgetAlertService(JdbcClient jdbc, BudgetService budgets, DeviceService devices, PushSender push,
                              UserAccountService users) {
        this.jdbc = jdbc;
        this.budgets = budgets;
        this.devices = devices;
        this.push = push;
        this.users = users;
    }

    /** Llamar dentro de la transacción después de crear o recategorizar un gasto. */
    public void afterExpense(UUID userId, UUID categoryId, UUID cardId, OffsetDateTime occurredAt) {
        LocalDate today = LocalDate.now(users.zoneOf(userId));
        LocalDate day = occurredAt.atZoneSameInstant(users.zoneOf(userId)).toLocalDate();
        if (day.getYear() != today.getYear() || day.getMonth() != today.getMonth()) return;
        LocalDate month = today.withDayOfMonth(1);
        LocalDate lastDay = today.withDayOfMonth(today.lengthOfMonth());
        List<PushSender.Message> messages = new ArrayList<>();

        BudgetResponse budget = budgets.current(userId);
        for (CategoryBudgetResponse category : budget.categories()) {
            if (!category.categoryId().equals(categoryId)) continue;
            Integer threshold = threshold(category.spentAmount(), category.effectiveAmount());
            if (threshold != null && claim(userId, "CATEGORY_BUDGET", categoryId, month, threshold)) {
                messages.add(categoryMessage(category, threshold, lastDay));
            }
        }

        CardLimit card = cardLimit(userId, cardId, month);
        if (card != null) {
            Integer threshold = threshold(card.spent(), card.limit());
            if (threshold != null && claim(userId, "CARD_LIMIT", cardId, month, threshold)) {
                messages.add(cardMessage(card, threshold));
            }
        }
        if (messages.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(userId, messages);
                }
            });
        } else {
            deliver(userId, messages);
        }
    }

    /** 100, 90 o null según el porcentaje gastado. */
    static Integer threshold(BigDecimal spent, BigDecimal limit) {
        if (spent == null || limit == null || limit.signum() <= 0) return null;
        BigDecimal percent = spent.multiply(HUNDRED).divide(limit, 2, RoundingMode.DOWN);
        if (percent.compareTo(HUNDRED) >= 0) return 100;
        if (percent.compareTo(BigDecimal.valueOf(90)) >= 0) return 90;
        return null;
    }

    /**
     * Reserva el aviso. true sólo si es la primera vez en el mes; con 100 también se
     * reserva el 90 para que no llegue después uno más suave que el que ya llegó.
     */
    private boolean claim(UUID userId, String kind, UUID targetId, LocalDate month, int threshold) {
        boolean first = insertLog(userId, kind, targetId, month, threshold);
        if (threshold == 100) insertLog(userId, kind, targetId, month, 90);
        return first;
    }

    private boolean insertLog(UUID userId, String kind, UUID targetId, LocalDate month, int threshold) {
        return jdbc.sql("""
                insert into notification_log (user_id, kind, target_id, period_month, threshold)
                values (:userId, :kind, :targetId, :month, :threshold)
                on conflict (user_id, kind, target_id, period_month, threshold) do nothing
                """).param("userId", userId).param("kind", kind).param("targetId", targetId)
                .param("month", month).param("threshold", threshold).update() > 0;
    }

    private record CardLimit(String name, BigDecimal limit, BigDecimal spent) { }

    private CardLimit cardLimit(UUID userId, UUID cardId, LocalDate month) {
        return jdbc.sql("""
                select coalesce(nullif(btrim(c.alias), ''), 'Tu tarjeta') as name, ba.amount_usd as limit_amount,
                       coalesce((select sum(e.amount) from expenses e
                                  where e.user_id = :userId and e.card_id = c.id
                                    and e.status = 'ACTIVE' and e.kind = 'EXPENSE' and e.currency_code = 'USD'
                                    and e.occurred_at >= (fp.period_month::timestamp at time zone u.timezone)
                                    and e.occurred_at < ((fp.period_month + interval '1 month') at time zone u.timezone)), 0) as spent
                  from cards c
                  join app_users u on u.id = c.user_id
                  join financial_periods fp on fp.user_id = c.user_id and fp.period_month = :month
                  join budget_allocations ba on ba.period_id = fp.id and ba.card_id = c.id and ba.scope = 'CARD' and ba.active
                 where c.id = :cardId and c.user_id = :userId
                """).param("userId", userId).param("cardId", cardId).param("month", month)
                .query((rs, rowNum) -> new CardLimit(rs.getString("name"), rs.getBigDecimal("limit_amount"),
                        rs.getBigDecimal("spent"))).optional().orElse(null);
    }

    private PushSender.Message categoryMessage(CategoryBudgetResponse category, int threshold, LocalDate lastDay) {
        String name = category.alias();
        BigDecimal spent = category.spentAmount();
        BigDecimal limit = category.effectiveAmount();
        Map<String, String> data = Map.of("type", "CATEGORY_BUDGET", "categoryId", category.categoryId().toString(),
                "threshold", String.valueOf(threshold));
        if (threshold == 100) {
            return new PushSender.Message(name + " pasó su presupuesto",
                    money(spent) + " de " + money(limit) + " este mes (" + percent(spent, limit) + " %).", data);
        }
        return new PushSender.Message(name + " llegó al 90 %",
                "Llevas " + money(spent) + " de " + money(limit) + ". Te quedan " + money(limit.subtract(spent))
                        + " hasta el " + lastDay.format(DAY_MONTH) + ".", data);
    }

    private PushSender.Message cardMessage(CardLimit card, int threshold) {
        Map<String, String> data = Map.of("type", "CARD_LIMIT", "threshold", String.valueOf(threshold));
        String body = money(card.spent()) + " de " + money(card.limit()) + " este mes (" + percent(card.spent(), card.limit()) + " %).";
        return new PushSender.Message(threshold == 100 ? card.name() + " pasó Mi límite"
                : card.name() + " llegó al 90 % de Mi límite", body, data);
    }

    private void deliver(UUID userId, List<PushSender.Message> messages) {
        List<String> tokens = devices.pushTokens(userId);
        if (tokens.isEmpty()) {
            log.info("budget_alert_no_device userId={} count={}", userId, messages.size());
            return;
        }
        for (PushSender.Message message : messages) {
            for (String token : tokens) {
                PushSender.Outcome outcome = push.send(token, message);
                if (outcome == PushSender.Outcome.INVALID_TOKEN) devices.forgetPushToken(token);
                log.info("budget_alert userId={} type={} outcome={}", userId, message.data().get("type"), outcome);
            }
        }
    }

    static String money(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(ES));
        return "$" + format.format(amount == null ? BigDecimal.ZERO : amount);
    }

    private static int percent(BigDecimal spent, BigDecimal limit) {
        return spent.multiply(HUNDRED).divide(limit, 0, RoundingMode.DOWN).intValue();
    }
}
