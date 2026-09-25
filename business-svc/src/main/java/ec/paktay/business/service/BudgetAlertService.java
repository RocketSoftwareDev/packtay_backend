package ec.paktay.business.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
import org.springframework.scheduling.annotation.Scheduled;
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
 * escribe con él) y se envía después del commit. Los intentos pendientes se
 * recuperan periódicamente cuando el usuario registra el token o Firebase vuelve.
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
                messages.add(cardMessage(card, cardId, month, threshold));
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
    private record Pending(UUID id, UUID userId, String kind, UUID targetId, LocalDate month, int threshold) { }
    private record Target(String kind, UUID id, LocalDate month) { }

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
            return message(name + " pasó su presupuesto",
                    money(spent) + " de " + money(limit) + " este mes (" + percent(spent, limit) + " %).",
                    data, "CATEGORY_BUDGET", category.categoryId(), lastDay.withDayOfMonth(1), threshold);
        }
        return message(name + " llegó al 90 %",
                "Llevas " + money(spent) + " de " + money(limit) + ". Te quedan " + money(limit.subtract(spent))
                        + " hasta el " + lastDay.format(DAY_MONTH) + ".", data, "CATEGORY_BUDGET",
                category.categoryId(), lastDay.withDayOfMonth(1), threshold);
    }

    private PushSender.Message cardMessage(CardLimit card, UUID cardId, LocalDate month, int threshold) {
        Map<String, String> data = Map.of("type", "CARD_LIMIT", "threshold", String.valueOf(threshold));
        String body = money(card.spent()) + " de " + money(card.limit()) + " este mes (" + percent(card.spent(), card.limit()) + " %).";
        return message(threshold == 100 ? card.name() + " pasó Mi límite"
                : card.name() + " llegó al 90 % de Mi límite", body, data, "CARD_LIMIT", cardId, month, threshold);
    }

    private PushSender.Message message(String title, String body, Map<String, String> data, String kind,
                                      UUID targetId, LocalDate month, int threshold) {
        Map<String, String> details = new java.util.HashMap<>(data);
        details.put("type", kind);
        details.put("threshold", String.valueOf(threshold));
        details.put("targetId", targetId.toString());
        details.put("periodMonth", month.toString());
        return new PushSender.Message(title, body, Map.copyOf(details));
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
                if (outcome == PushSender.Outcome.SENT) markDelivered(userId, message);
                log.info("budget_alert userId={} type={} outcome={}", userId, message.data().get("type"), outcome);
            }
        }
    }

    /** Reintenta filas no entregadas al disponer de token/servicio; sent_at actúa como lease. */
    @Scheduled(fixedDelayString = "${paktay.push.retry-delay-ms:60000}")
    public void retryPending() {
        retryPending(null);
    }

    public void retryPending(UUID onlyUserId) {
        if (!push.enabled()) return;
        List<Pending> pending = claimPending(onlyUserId);

        Map<Target, List<Pending>> targets = new LinkedHashMap<>();
        for (Pending item : pending) {
            targets.computeIfAbsent(new Target(item.kind(), item.targetId(), item.month()), ignored -> new ArrayList<>())
                    .add(item);
        }
        Map<UUID, List<String>> tokensByUser = new java.util.HashMap<>();
        Map<UUID, BudgetResponse> budgetsByUser = new java.util.HashMap<>();
        for (Map.Entry<Target, List<Pending>> entry : targets.entrySet()) {
            Target target = entry.getKey();
            UUID userId = entry.getValue().get(0).userId();
            List<String> tokens = tokensByUser.computeIfAbsent(userId, devices::pushTokens);
            if (tokens.isEmpty()) continue;
            LocalDate currentMonth = LocalDate.now(users.zoneOf(userId)).withDayOfMonth(1);
            if (!currentMonth.equals(target.month())) {
                deletePending(entry.getValue());
                continue;
            }

            Integer currentThreshold;
            PushSender.Message message;
            if ("CATEGORY_BUDGET".equals(target.kind())) {
                BudgetResponse budget = budgetsByUser.computeIfAbsent(userId, budgets::current);
                CategoryBudgetResponse category = budget.categories().stream()
                        .filter(item -> item.categoryId().equals(target.id())).findFirst().orElse(null);
                if (category == null) {
                    deletePending(entry.getValue());
                    continue;
                }
                currentThreshold = threshold(category.spentAmount(), category.effectiveAmount());
                message = currentThreshold == null ? null : categoryMessage(category, currentThreshold,
                        target.month().plusMonths(1).minusDays(1));
            } else {
                CardLimit card = cardLimit(userId, target.id(), target.month());
                currentThreshold = card == null ? null : threshold(card.spent(), card.limit());
                message = currentThreshold == null ? null : cardMessage(card, target.id(), target.month(), currentThreshold);
            }
            Integer sendThreshold = retryThreshold(entry.getValue().stream().map(Pending::threshold).toList(), currentThreshold);
            if (sendThreshold == null) {
                deletePending(entry.getValue());
                continue;
            }
            boolean sent = false;
            for (String token : tokens) {
                PushSender.Outcome outcome = push.send(token, message);
                if (outcome == PushSender.Outcome.INVALID_TOKEN) devices.forgetPushToken(token);
                if (outcome == PushSender.Outcome.SENT) sent = true;
                log.info("budget_alert_retry userId={} type={} outcome={}", userId, target.kind(), outcome);
            }
            if (sent) markDelivered(userId, target, sendThreshold);
        }
    }

    private List<Pending> claimPending(UUID onlyUserId) {
        String userFilter = onlyUserId == null ? "" : "and candidate.user_id = :userId";
        var statement = jdbc.sql(("""
                update notification_log n set sent_at = now()
                 where n.id in (
                     select candidate.id from notification_log candidate
                      where not candidate.delivered
                        and candidate.sent_at < now() - interval '1 minute'
                        and exists (select 1 from user_devices d where d.user_id = candidate.user_id and d.push_token is not null)
                        %s
                      order by candidate.sent_at
                      limit 100
                      for update skip locked
                 )
                returning n.id, n.user_id, n.kind, n.target_id, n.period_month, n.threshold
                """).formatted(userFilter));
        if (onlyUserId != null) statement = statement.param("userId", onlyUserId);
        return statement.query((rs, rowNum) -> new Pending(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("kind"), rs.getObject("target_id", UUID.class),
                rs.getObject("period_month", LocalDate.class), rs.getInt("threshold"))).list();
    }

    static Integer retryThreshold(List<Integer> pendingThresholds, Integer currentThreshold) {
        if (currentThreshold == null || pendingThresholds.isEmpty()) return null;
        int required = pendingThresholds.stream().max(Comparator.naturalOrder()).orElse(100);
        return currentThreshold >= required ? currentThreshold : null;
    }

    private void markDelivered(UUID userId, PushSender.Message message) {
        UUID targetId = UUID.fromString(message.data().get("targetId"));
        LocalDate month = LocalDate.parse(message.data().get("periodMonth"));
        int threshold = Integer.parseInt(message.data().get("threshold"));
        markDelivered(userId, new Target(message.data().get("type"), targetId, month), threshold);
    }

    private void markDelivered(UUID userId, Target target, int threshold) {
        jdbc.sql("""
                insert into notification_log (user_id, kind, target_id, period_month, threshold, sent_at, delivered)
                values (:userId, :kind, :targetId, :month, :threshold, now(), true)
                on conflict (user_id, kind, target_id, period_month, threshold)
                do update set sent_at = now(), delivered = true
                """).param("userId", userId).param("kind", target.kind()).param("targetId", target.id())
                .param("month", target.month()).param("threshold", threshold).update();
        jdbc.sql("""
                update notification_log set delivered = true, sent_at = now()
                 where user_id = :userId and kind = :kind and target_id = :targetId
                   and period_month = :month and threshold <= :threshold
                """).param("userId", userId).param("kind", target.kind()).param("targetId", target.id())
                .param("month", target.month()).param("threshold", threshold).update();
    }

    private void deletePending(List<Pending> pending) {
        for (Pending item : pending) {
            jdbc.sql("delete from notification_log where id = :id and not delivered")
                    .param("id", item.id()).update();
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
