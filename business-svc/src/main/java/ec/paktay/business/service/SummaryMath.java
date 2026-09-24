package ec.paktay.business.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Reglas de cálculo del resumen del mes (decisión del dueño, día 5). Única fuente
 * para todas las pantallas: el resumen de inicio y los totales de
 * GET /api/v1/user/budgets/current usan estas mismas funciones.
 *
 * Clase pura: sin base, sin Spring y sin reloj propio (el "hoy" llega como
 * parámetro), para poder probarla con JUnit sin contexto.
 *
 * - Presupuesto de una categoría seleccionada: su monto propio si lo tiene; si no,
 *   el global. El global es un monto por categoría, no un tope ni una bolsa.
 * - Presupuesto del mes: suma de los presupuestos efectivos de las categorías
 *   activas seleccionadas (global 100, 10 categorías, una con 50 = 950).
 * - Porcentajes: siempre redondeados hacia abajo (99.6 se muestra 99, nunca 100).
 */
public final class SummaryMath {
    /** Ritmo: FAST cuando el porcentaje gastado supera en más de esto al del mes transcurrido. */
    public static final int FAST_MARGIN_POINTS = 10;
    /** Estado AT_LIMIT desde este porcentaje (incluye exactamente 100). */
    public static final int AT_LIMIT_PERCENT = 90;

    public static final String SOURCE_OWN = "OWN";
    public static final String SOURCE_GLOBAL = "GLOBAL";

    public static final String PACE_NONE = "NONE";
    public static final String PACE_OK = "OK";
    public static final String PACE_FAST = "FAST";
    public static final String PACE_OVER = "OVER";

    public static final String STATUS_NO_BUDGET = "NO_BUDGET";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_AT_LIMIT = "AT_LIMIT";
    public static final String STATUS_OVER = "OVER";

    private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Tope defensivo para no desbordar int con un presupuesto diminuto. */
    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(Integer.MAX_VALUE);

    private SummaryMath() { }

    /** Categoría dentro del cálculo: si está activa, si está seleccionada y su monto propio (null = usa el global). */
    public record CategoryBudget(boolean categoryActive, boolean selected, BigDecimal ownAmount) { }

    /**
     * Calendario del mes consultado visto desde "hoy" en la zona del usuario.
     * daysLeft cuenta el día de hoy (24 de septiembre: quedan 7).
     */
    public record MonthClock(YearMonth month, int daysInMonth, int dayOfMonth, int daysLeft,
                             int elapsedPercent, LocalDate resetsOn, boolean current) { }

    /**
     * Interpreta ?month=YYYY-MM. Null o vacío = mes actual del usuario. Formato
     * incorrecto o mes futuro: IllegalArgumentException (400).
     */
    public static YearMonth resolveMonth(String raw, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        if (raw == null || raw.isBlank()) return current;
        String value = raw.trim();
        YearMonth month;
        try {
            if (!MONTH.matcher(value).matches()) throw new DateTimeException(value);
            month = YearMonth.parse(value);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("month debe tener el formato YYYY-MM, por ejemplo 2026-09");
        }
        if (month.isAfter(current)) {
            throw new IllegalArgumentException("No se puede consultar el resumen de un mes futuro");
        }
        return month;
    }

    /**
     * Mes actual: dayOfMonth = hoy, daysLeft = días del mes - hoy + 1.
     * Mes pasado: dayOfMonth = días del mes, daysLeft = 0, elapsedPercent = 100.
     */
    public static MonthClock clock(YearMonth month, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        if (month.isAfter(current)) {
            throw new IllegalArgumentException("No se puede consultar el resumen de un mes futuro");
        }
        int daysInMonth = month.lengthOfMonth();
        boolean isCurrent = month.equals(current);
        int dayOfMonth = isCurrent ? today.getDayOfMonth() : daysInMonth;
        int daysLeft = isCurrent ? daysInMonth - dayOfMonth + 1 : 0;
        int elapsedPercent = (dayOfMonth * 100) / daysInMonth;
        return new MonthClock(month, daysInMonth, dayOfMonth, daysLeft, elapsedPercent,
                month.plusMonths(1).atDay(1), isCurrent);
    }

    /** Monto propio si lo hay; si no, el global; null si ninguno de los dos existe. */
    public static BigDecimal effectiveBudget(BigDecimal ownAmount, BigDecimal globalAmount) {
        if (ownAmount != null) return ownAmount;
        return globalAmount;
    }

    /** OWN, GLOBAL o null (sin presupuesto efectivo). */
    public static String budgetSource(BigDecimal ownAmount, BigDecimal globalAmount) {
        if (ownAmount != null) return SOURCE_OWN;
        if (globalAmount != null) return SOURCE_GLOBAL;
        return null;
    }

    /**
     * Presupuesto del mes: suma del efectivo de cada categoría activa y seleccionada.
     * Null si ninguna aporta un monto (no hay presupuesto).
     */
    public static BigDecimal totalBudget(Collection<CategoryBudget> categories, BigDecimal globalAmount) {
        BigDecimal total = null;
        for (CategoryBudget category : categories) {
            if (!category.categoryActive() || !category.selected()) continue;
            BigDecimal effective = effectiveBudget(category.ownAmount(), globalAmount);
            if (effective == null) continue;
            total = total == null ? effective : total.add(effective);
        }
        return total;
    }

    /** spent / budget * 100 redondeado hacia abajo; null sin presupuesto (o presupuesto 0). */
    public static Integer percent(BigDecimal spent, BigDecimal budget) {
        if (budget == null || budget.signum() <= 0) return null;
        return safe(spent).multiply(HUNDRED).divide(budget, 0, RoundingMode.FLOOR)
                .min(MAX_PERCENT).intValue();
    }

    /** budget - spent (puede ser negativo); null sin presupuesto. */
    public static BigDecimal available(BigDecimal spent, BigDecimal budget) {
        if (budget == null) return null;
        return budget.subtract(safe(spent));
    }

    /** max(spent - budget, 0); 0 sin presupuesto. */
    public static BigDecimal overBy(BigDecimal spent, BigDecimal budget) {
        if (budget == null) return BigDecimal.ZERO;
        BigDecimal over = safe(spent).subtract(budget);
        return over.signum() > 0 ? over : BigDecimal.ZERO;
    }

    /** NONE sin presupuesto; OVER si gastó más; FAST si va más de 10 puntos por delante del mes; OK en el resto. */
    public static String pace(BigDecimal spent, BigDecimal budget, int elapsedPercent) {
        Integer percent = percent(spent, budget);
        if (percent == null) return PACE_NONE;
        if (safe(spent).compareTo(budget) > 0) return PACE_OVER;
        if (percent > elapsedPercent + FAST_MARGIN_POINTS) return PACE_FAST;
        return PACE_OK;
    }

    /** NO_BUDGET sin presupuesto efectivo; OVER si gastó más; AT_LIMIT desde 90 % (incluye 100 %); OK en el resto. */
    public static String categoryStatus(BigDecimal spent, BigDecimal budget) {
        Integer percent = percent(spent, budget);
        if (percent == null) return STATUS_NO_BUDGET;
        if (safe(spent).compareTo(budget) > 0) return STATUS_OVER;
        if (percent >= AT_LIMIT_PERCENT) return STATUS_AT_LIMIT;
        return STATUS_OK;
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
