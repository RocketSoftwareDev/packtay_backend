package ec.paktay.business.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trabajo de los pagos recurrentes (día 8a), cada media hora:
 *
 * 1. Deja PENDING los cobros que ya tocan, en la zona horaria de cada usuario, aunque no
 *    abra la app.
 * 2. Aviso de la víspera a las 19:00 de su zona: uno por fecha. Con un solo cobro dice
 *    cuál («Mañana se cobra Netflix»); con varios, los agrupa («Mañana se cobran 3 pagos
 *    recurrentes»). recurring_reminders evita repetirlo. El día del cobro no hay aviso: ya
 *    está en «Por revisar».
 */
@Component
public class RecurringJob {
    private static final Logger log = LoggerFactory.getLogger(RecurringJob.class);
    static final int REMINDER_HOUR = 19;
    static final String KIND = "RECURRING_REMINDER";

    private final JdbcClient jdbc;
    private final RecurringPaymentService recurring;
    private final UserAccountService users;
    private final DeviceService devices;
    private final PushSender push;
    private final PushDeliveryLogService deliveryLog;

    public RecurringJob(JdbcClient jdbc, RecurringPaymentService recurring, UserAccountService users, DeviceService devices,
                        PushSender push, PushDeliveryLogService deliveryLog) {
        this.jdbc = jdbc;
        this.recurring = recurring;
        this.users = users;
        this.devices = devices;
        this.push = push;
        this.deliveryLog = deliveryLog;
    }

    @Scheduled(cron = "0 */30 * * * *")
    public void run() {
        List<UUID> userIds = jdbc.sql("select distinct user_id from recurring_payments where status = 'ACTIVE'")
                .query(UUID.class).list();
        int created = 0;
        int reminded = 0;
        for (UUID userId : userIds) {
            try {
                created += recurring.generateDue(userId);
                if (remind(userId)) reminded++;
            } catch (RuntimeException ex) {
                log.warn("recurring_job_user_failed userId={} reason={}", userId, ex.getClass().getSimpleName());
            }
        }
        if (created > 0 || reminded > 0) log.info("recurring_job occurrences={} reminders={}", created, reminded);
    }

    /** true si mandó (o intentó mandar) el aviso de mañana. */
    boolean remind(UUID userId) {
        ZonedDateTime now = ZonedDateTime.now(users.zoneOf(userId));
        if (now.getHour() != REMINDER_HOUR) return false;
        LocalDate tomorrow = now.toLocalDate().plusDays(1);
        List<RecurringPaymentService.Row> due = recurring.dueOn(userId, tomorrow);
        if (due.isEmpty()) return false;
        int claimed = jdbc.sql("""
                insert into recurring_reminders (user_id, due_date) values (:userId, :due)
                on conflict (user_id, due_date) do nothing
                """).param("userId", userId).param("due", tomorrow).update();
        if (claimed == 0) return false;
        PushSender.Message message = message(due, tomorrow);
        for (DeviceService.PushTarget target : devices.pushTargets(userId)) {
            PushSender.Outcome outcome = push.send(target.token(), message);
            deliveryLog.record(userId, target.deviceId(), KIND, outcome);
            if (outcome == PushSender.Outcome.INVALID_TOKEN) devices.forgetPushToken(target.token());
        }
        return true;
    }

    static PushSender.Message message(List<RecurringPaymentService.Row> due, LocalDate date) {
        Map<String, String> data = Map.of("type", KIND, "dueDate", date.toString());
        if (due.size() == 1) {
            RecurringPaymentService.Row r = due.get(0);
            return new PushSender.Message("Mañana se cobra " + r.name(),
                    BudgetAlertService.money(r.amount()) + " con " + r.cardName() + ". Aparecerá en «Por revisar» para confirmarlo.", data);
        }
        BigDecimal total = due.stream().map(RecurringPaymentService.Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> names = due.stream().map(RecurringPaymentService.Row::name).toList();
        String joined = names.size() <= 3
                ? String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.get(names.size() - 1)
                : String.join(", ", names.subList(0, 3)) + " y " + (names.size() - 3) + " más";
        return new PushSender.Message("Mañana se cobran " + due.size() + " pagos recurrentes",
                joined + " · " + BudgetAlertService.money(total) + ". Revísalos en Movimientos › Recurrentes.", data);
    }
}
