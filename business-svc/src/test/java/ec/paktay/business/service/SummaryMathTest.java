package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pruebas unitarias puras (sin base ni Spring) de las reglas del resumen del mes. */
class SummaryMathTest {
    private static final LocalDate SEPT_24 = LocalDate.of(2026, 9, 24);

    private static BigDecimal usd(String value) { return new BigDecimal(value); }

    // --- presupuesto efectivo -------------------------------------------------

    @Test
    void globalIsPerCategoryAndOwnAmountReplacesIt() {
        List<SummaryMath.CategoryBudget> categories = new ArrayList<>();
        for (int i = 0; i < 9; i++) categories.add(new SummaryMath.CategoryBudget(true, true, null));
        categories.add(new SummaryMath.CategoryBudget(true, true, usd("50")));

        assertEquals(0, usd("950").compareTo(SummaryMath.totalBudget(categories, usd("100"))));
    }

    @Test
    void inactiveOrUnselectedCategoriesDoNotAddBudget() {
        List<SummaryMath.CategoryBudget> categories = List.of(
                new SummaryMath.CategoryBudget(true, true, null),
                new SummaryMath.CategoryBudget(false, true, usd("70")),
                new SummaryMath.CategoryBudget(true, false, usd("80")));

        assertEquals(0, usd("100").compareTo(SummaryMath.totalBudget(categories, usd("100"))));
    }

    @Test
    void withoutGlobalOnlyOwnAmountsCountAndNothingMeansNoBudget() {
        List<SummaryMath.CategoryBudget> mixed = List.of(
                new SummaryMath.CategoryBudget(true, true, null),
                new SummaryMath.CategoryBudget(true, true, usd("40")));
        assertEquals(0, usd("40").compareTo(SummaryMath.totalBudget(mixed, null)));

        assertNull(SummaryMath.totalBudget(List.of(new SummaryMath.CategoryBudget(true, true, null)), null));
        assertNull(SummaryMath.totalBudget(List.of(), usd("100")));
    }

    @Test
    void budgetSourceTellsOwnFromGlobal() {
        assertEquals("OWN", SummaryMath.budgetSource(usd("50"), usd("100")));
        assertEquals("GLOBAL", SummaryMath.budgetSource(null, usd("100")));
        assertNull(SummaryMath.budgetSource(null, null));
        assertEquals(0, usd("50").compareTo(SummaryMath.effectiveBudget(usd("50"), usd("100"))));
        assertEquals(0, usd("100").compareTo(SummaryMath.effectiveBudget(null, usd("100"))));
    }

    // --- porcentaje y montos -------------------------------------------------

    @Test
    void percentIsRoundedDown() {
        assertEquals(99, SummaryMath.percent(usd("99.60"), usd("100")));
        assertEquals(99, SummaryMath.percent(usd("995.99"), usd("1000")));
        assertEquals(33, SummaryMath.percent(usd("1"), usd("3")));
        assertEquals(100, SummaryMath.percent(usd("100"), usd("100")));
        assertEquals(0, SummaryMath.percent(BigDecimal.ZERO, usd("100")));
        assertNull(SummaryMath.percent(usd("10"), null));
    }

    @Test
    void availableCanBeNegativeAndOverByNeverIs() {
        assertEquals(0, usd("-20").compareTo(SummaryMath.available(usd("120"), usd("100"))));
        assertEquals(0, usd("20").compareTo(SummaryMath.overBy(usd("120"), usd("100"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(SummaryMath.overBy(usd("80"), usd("100"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(SummaryMath.overBy(usd("80"), null)));
        assertNull(SummaryMath.available(usd("80"), null));
    }

    // --- estado de categoría ---------------------------------------------------

    @Test
    void categoryStatusThresholds() {
        assertEquals("NO_BUDGET", SummaryMath.categoryStatus(usd("10"), null));
        assertEquals("OK", SummaryMath.categoryStatus(usd("89.99"), usd("100")));
        assertEquals("AT_LIMIT", SummaryMath.categoryStatus(usd("90"), usd("100")));
        assertEquals("AT_LIMIT", SummaryMath.categoryStatus(usd("100"), usd("100")), "exactamente 100 % es AT_LIMIT, no OVER");
        assertEquals("OVER", SummaryMath.categoryStatus(usd("100.01"), usd("100")));
    }

    // --- ritmo -----------------------------------------------------------------

    @Test
    void paceNoneWithoutBudget() {
        assertEquals("NONE", SummaryMath.pace(usd("50"), null, 80));
    }

    @Test
    void paceOverWhenSpentExceedsBudget() {
        assertEquals("OVER", SummaryMath.pace(usd("100.01"), usd("100"), 50));
    }

    @Test
    void paceFastOnlyAboveTenPointsAhead() {
        // 24 de septiembre: elapsedPercent = 80.
        assertEquals("OK", SummaryMath.pace(usd("90"), usd("100"), 80), "90 no supera 80 + 10");
        assertEquals("FAST", SummaryMath.pace(usd("91"), usd("100"), 80));
    }

    @Test
    void paceOkAtExactlyTheBudget() {
        assertEquals("OK", SummaryMath.pace(usd("100"), usd("100"), 100));
    }

    // --- calendario ------------------------------------------------------------

    @Test
    void daysLeftCountsToday() {
        SummaryMath.MonthClock clock = SummaryMath.clock(YearMonth.of(2026, 9), SEPT_24);

        assertEquals(30, clock.daysInMonth());
        assertEquals(24, clock.dayOfMonth());
        assertEquals(7, clock.daysLeft());
        assertEquals(80, clock.elapsedPercent());
        assertEquals(LocalDate.of(2026, 10, 1), clock.resetsOn());
        assertTrue(clock.current());
    }

    @Test
    void lastDayStillHasOneDayLeftAndFirstDayHasAll() {
        assertEquals(1, SummaryMath.clock(YearMonth.of(2026, 9), LocalDate.of(2026, 9, 30)).daysLeft());
        SummaryMath.MonthClock first = SummaryMath.clock(YearMonth.of(2026, 9), LocalDate.of(2026, 9, 1));
        assertEquals(30, first.daysLeft());
        assertEquals(3, first.elapsedPercent(), "1/30 = 3.33 se redondea hacia abajo");
    }

    @Test
    void pastMonthIsClosed() {
        SummaryMath.MonthClock clock = SummaryMath.clock(YearMonth.of(2026, 2), SEPT_24);

        assertEquals(28, clock.daysInMonth());
        assertEquals(28, clock.dayOfMonth());
        assertEquals(0, clock.daysLeft());
        assertEquals(100, clock.elapsedPercent());
        assertEquals(LocalDate.of(2026, 3, 1), clock.resetsOn());
        assertFalse(clock.current());
    }

    @Test
    void futureMonthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SummaryMath.clock(YearMonth.of(2026, 10), SEPT_24));
        assertThrows(IllegalArgumentException.class, () -> SummaryMath.resolveMonth("2026-10", SEPT_24));
    }

    @Test
    void resolveMonthDefaultsToCurrent() {
        assertEquals(YearMonth.of(2026, 9), SummaryMath.resolveMonth(null, SEPT_24));
        assertEquals(YearMonth.of(2026, 9), SummaryMath.resolveMonth("  ", SEPT_24));
        assertEquals(YearMonth.of(2025, 12), SummaryMath.resolveMonth("2025-12", SEPT_24));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-9", "2026/09", "09-2026", "2026-13", "2026-00", "2026-09-01", "abc"})
    void malformedMonthIsRejected(String raw) {
        assertThrows(IllegalArgumentException.class, () -> SummaryMath.resolveMonth(raw, SEPT_24));
    }
}
