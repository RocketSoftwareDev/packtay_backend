package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

class RecurrenceScheduleTest {
    private static RecurrenceSchedule.Rule monthly(String rule, Integer day) {
        return new RecurrenceSchedule.Rule("MONTHLY", rule, day, null, null);
    }

    @Test
    void diaElegidoQueElMesNoTieneCaeAlUltimo() {
        var rule = monthly("DAY", 31);
        assertEquals(LocalDate.of(2026, 9, 30), RecurrenceSchedule.dueIn(rule, YearMonth.of(2026, 9)));
        assertEquals(LocalDate.of(2027, 2, 28), RecurrenceSchedule.dueIn(rule, YearMonth.of(2027, 2)));
        assertEquals(LocalDate.of(2026, 10, 31), RecurrenceSchedule.dueIn(rule, YearMonth.of(2026, 10)));
    }

    @Test
    void primeroYFinDeMes() {
        assertEquals(LocalDate.of(2026, 10, 1), RecurrenceSchedule.dueIn(monthly("FIRST", null), YearMonth.of(2026, 10)));
        assertEquals(LocalDate.of(2028, 2, 29), RecurrenceSchedule.dueIn(monthly("LAST", null), YearMonth.of(2028, 2)));
    }

    @Test
    void anualSoloEnSuMes() {
        var rule = new RecurrenceSchedule.Rule("YEARLY", "DAY", 15, 3, null);
        assertNull(RecurrenceSchedule.dueIn(rule, YearMonth.of(2026, 10)));
        assertEquals(LocalDate.of(2027, 3, 15), RecurrenceSchedule.nextDue(rule, LocalDate.of(2026, 9, 30)));
    }

    @Test
    void fechaDeFinInclusive() {
        var rule = new RecurrenceSchedule.Rule("MONTHLY", "DAY", 15, null, LocalDate.of(2026, 11, 1));
        assertEquals(List.of(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 15)),
                RecurrenceSchedule.dueDatesBetween(rule, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 3, 1)));
        assertNull(RecurrenceSchedule.nextDue(rule, LocalDate.of(2026, 11, 16)));
    }

    @Test
    void rangoInclusivoEnLosDosExtremos() {
        var rule = monthly("DAY", 15);
        assertEquals(List.of(LocalDate.of(2026, 9, 15)),
                RecurrenceSchedule.dueDatesBetween(rule, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 15)));
        assertEquals(List.of(), RecurrenceSchedule.dueDatesBetween(rule, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 14)));
    }

    @Test
    void anualSeRepartePorMes() {
        var rule = new RecurrenceSchedule.Rule("YEARLY", "FIRST", null, 1, null);
        assertEquals(new java.math.BigDecimal("10.00"), RecurrenceSchedule.monthlyEquivalent(rule, new java.math.BigDecimal("120")));
    }
}
