package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ManualDateWindowTest {
    private static final ZoneId GYE = ZoneId.of("America/Guayaquil");
    // 24 de septiembre de 2026, 10:00 en Guayaquil.
    private static final Instant NOW = OffsetDateTime.parse("2026-09-24T10:00:00-05:00").toInstant();

    @Test
    void aceptaHoyAyerYHastaSieteDiasAtras() {
        assertDoesNotThrow(() -> ManualDateWindow.validate(OffsetDateTime.parse("2026-09-24T09:59:00-05:00"), GYE, NOW));
        assertDoesNotThrow(() -> ManualDateWindow.validate(OffsetDateTime.parse("2026-09-23T10:00:00-05:00"), GYE, NOW));
        assertDoesNotThrow(() -> ManualDateWindow.validate(OffsetDateTime.parse("2026-09-17T00:00:00-05:00"), GYE, NOW));
    }

    @Test
    void rechazaOchoDiasAtras() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualDateWindow.validate(OffsetDateTime.parse("2026-09-16T23:59:00-05:00"), GYE, NOW));
    }

    @Test
    void usaElCalendarioDelUsuarioYNoUtc() {
        // 17 de septiembre 04:00 UTC es el 16 a las 23:00 en Guayaquil: fuera.
        assertThrows(IllegalArgumentException.class,
                () -> ManualDateWindow.validate(OffsetDateTime.parse("2026-09-17T04:00:00Z"), GYE, NOW));
    }

    @Test
    void rechazaElFuturoSalvoCincoMinutosDeReloj() {
        assertDoesNotThrow(() -> ManualDateWindow.validate(OffsetDateTime.parse("2026-09-24T10:04:00-05:00"), GYE, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> ManualDateWindow.validate(OffsetDateTime.parse("2026-09-24T10:06:00-05:00"), GYE, NOW));
    }
}
