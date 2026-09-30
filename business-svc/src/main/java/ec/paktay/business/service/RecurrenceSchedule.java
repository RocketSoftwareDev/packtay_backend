package ec.paktay.business.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Fechas de cobro de un pago recurrente (día 8a). Sólo aritmética de calendario, sin base.
 *
 * - MONTHLY: un cobro por mes. YEARLY: un cobro al año, en monthOfYear.
 * - FIRST: día 1. LAST: último día del mes. DAY: el día elegido o, si el mes no lo tiene
 *   (31 en abril, 30 en febrero), el último.
 * - endMonth (opcional): el último mes con cobro, inclusive.
 */
public final class RecurrenceSchedule {

    public record Rule(String frequency, String dayRule, Integer dayOfMonth, Integer monthOfYear, LocalDate endMonth) {
        boolean yearly() {
            return "YEARLY".equals(frequency);
        }
    }

    private RecurrenceSchedule() { }

    /** La fecha de cobro en ese mes, o null si ese mes no tiene cobro (anual en otro mes o pasado el fin). */
    public static LocalDate dueIn(Rule rule, YearMonth month) {
        if (rule.yearly() && (rule.monthOfYear() == null || month.getMonthValue() != rule.monthOfYear())) return null;
        if (rule.endMonth() != null && month.isAfter(YearMonth.from(rule.endMonth()))) return null;
        int length = month.lengthOfMonth();
        int day = switch (rule.dayRule()) {
            case "FIRST" -> 1;
            case "LAST" -> length;
            default -> Math.min(rule.dayOfMonth() == null ? 1 : rule.dayOfMonth(), length);
        };
        return month.atDay(day);
    }

    /** Fechas de cobro entre from y to, las dos inclusive, en orden. */
    public static List<LocalDate> dueDatesBetween(Rule rule, LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        if (to.isBefore(from)) return dates;
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            LocalDate due = dueIn(rule, month);
            if (due != null && !due.isBefore(from) && !due.isAfter(to)) dates.add(due);
        }
        return dates;
    }

    /** El próximo cobro desde from (inclusive), o null si ya terminó. Mira hasta 13 meses. */
    public static LocalDate nextDue(Rule rule, LocalDate from) {
        List<LocalDate> dates = dueDatesBetween(rule, from, from.plusMonths(13));
        return dates.isEmpty() ? null : dates.get(0);
    }

    /** Cuánto vale al mes (el anual se reparte en 12), para el total de la lista. */
    public static java.math.BigDecimal monthlyEquivalent(Rule rule, java.math.BigDecimal amount) {
        return rule.yearly() ? amount.divide(java.math.BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP) : amount;
    }
}
