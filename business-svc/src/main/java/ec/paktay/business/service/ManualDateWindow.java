package ec.paktay.business.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Fecha permitida para un gasto manual: de hoy hasta {@link #DAYS_BACK} días atrás
 * en el calendario del usuario. "Hoy" cuenta como día 0, así que desde el 24 se
 * puede elegir hasta el 17. Se admiten {@link #CLOCK_SKEW} de adelanto por la
 * diferencia de reloj entre el teléfono y el servidor. Las capturas de Wallet no
 * pasan por aquí: conservan la fecha real del pago aunque se revisen tarde.
 */
public final class ManualDateWindow {
    public static final int DAYS_BACK = 7;
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private ManualDateWindow() { }

    public static void validate(OffsetDateTime occurredAt, ZoneId zone, Instant now) {
        if (occurredAt.toInstant().isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("La fecha de un gasto manual no puede ser futura");
        }
        LocalDate day = occurredAt.atZoneSameInstant(zone).toLocalDate();
        LocalDate oldest = now.atZone(zone).toLocalDate().minusDays(DAYS_BACK);
        if (day.isBefore(oldest)) {
            throw new IllegalArgumentException("Un gasto manual puede registrarse hasta " + DAYS_BACK + " días atrás");
        }
    }
}
