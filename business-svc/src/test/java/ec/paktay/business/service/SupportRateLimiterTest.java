package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class SupportRateLimiterTest {
    /** Reloj que se puede adelantar a mano. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-09-25T10:00:00Z");

        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void permiteHastaElMaximoYDespuesRechaza() {
        SupportRateLimiter limiter = new SupportRateLimiter(new MovableClock());
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquire("ip:1.1.1.1", 5, Duration.ofHours(1)));
        assertFalse(limiter.tryAcquire("ip:1.1.1.1", 5, Duration.ofHours(1)));
        assertTrue(limiter.tryAcquire("ip:2.2.2.2", 5, Duration.ofHours(1)));
    }

    @Test
    void laVentanaSeLibera() {
        MovableClock clock = new MovableClock();
        SupportRateLimiter limiter = new SupportRateLimiter(clock);
        for (int i = 0; i < 3; i++) limiter.tryAcquire("email:a@b.com", 3, Duration.ofDays(1));
        assertFalse(limiter.tryAcquire("email:a@b.com", 3, Duration.ofDays(1)));
        clock.advance(Duration.ofDays(1).plusMinutes(1));
        assertTrue(limiter.tryAcquire("email:a@b.com", 3, Duration.ofDays(1)));
    }

    @Test
    void losRechazosTambienCuentanParaElBloqueoAutomatico() {
        SupportRateLimiter limiter = new SupportRateLimiter(new MovableClock());
        for (int i = 0; i < 25; i++) limiter.tryAcquire("ip:9.9.9.9", 5, Duration.ofHours(1));
        assertEquals(25, limiter.count("ip:9.9.9.9", Duration.ofHours(1)));
    }
}
