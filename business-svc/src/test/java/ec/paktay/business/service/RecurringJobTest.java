package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RecurringJobTest {
    private static RecurringPaymentService.Row row(String name, String amount) {
        return new RecurringPaymentService.Row(UUID.randomUUID(), name, new BigDecimal(amount), "USD", UUID.randomUUID(),
                "Visa Pichincha", "ACTIVE", UUID.randomUUID(), "Suscripciones", "MONTHLY", "DAY", 15, null, null,
                LocalDate.of(2026, 9, 1), "ACTIVE");
    }

    @Test
    void unSoloCobroLoNombra() {
        PushSender.Message m = RecurringJob.message(List.of(row("Netflix", "15.99")), LocalDate.of(2026, 10, 15));
        assertEquals("Mañana se cobra Netflix", m.title());
        assertEquals("$15,99 con Visa Pichincha. Aparecerá en «Por revisar» para confirmarlo.", m.body());
    }

    @Test
    void variosCobrosVanEnUnSoloAviso() {
        PushSender.Message m = RecurringJob.message(
                List.of(row("Netflix", "15.99"), row("Spotify", "5.99"), row("Arriendo", "350")), LocalDate.of(2026, 10, 1));
        assertEquals("Mañana se cobran 3 pagos recurrentes", m.title());
        assertEquals("Netflix, Spotify y Arriendo · $371,98. Revísalos en Movimientos › Recurrentes.", m.body());
    }

    @Test
    void elAvisoSaleDesdeLas19HastaMedianoche() {
        assertEquals(false, RecurringJob.inReminderWindow(18));
        assertEquals(true, RecurringJob.inReminderWindow(19));
        assertEquals(true, RecurringJob.inReminderWindow(20));
        assertEquals(true, RecurringJob.inReminderWindow(23));
    }

    @Test
    void masDeTresSeResumen() {
        PushSender.Message m = RecurringJob.message(List.of(row("A", "1"), row("B", "1"), row("C", "1"), row("D", "1"), row("E", "1")),
                LocalDate.of(2026, 10, 1));
        assertEquals("A, B, C y 2 más · $5,00. Revísalos en Movimientos › Recurrentes.", m.body());
    }
}
