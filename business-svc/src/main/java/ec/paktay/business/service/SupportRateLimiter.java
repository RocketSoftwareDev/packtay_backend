package ec.paktay.business.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Límite de envíos del formulario público, en memoria (hay un solo servidor). Si el
 * servicio se reinicia los contadores vuelven a cero; lo acepta la beta. Cloudflare
 * pondrá su propia regla delante cuando se despliegue.
 */
@Component
public class SupportRateLimiter {
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public SupportRateLimiter() {
        this(Clock.systemUTC());
    }

    SupportRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Cuenta un intento y dice si sigue dentro del límite (max intentos por ventana). */
    public boolean tryAcquire(String key, int max, Duration window) {
        long now = clock.millis();
        long since = now - window.toMillis();
        Deque<Long> times = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < since) times.pollFirst();
            times.addLast(now);
            return times.size() <= max;
        }
    }

    /** Intentos en la ventana, sin contar uno nuevo. */
    public int count(String key, Duration window) {
        Deque<Long> times = hits.get(key);
        if (times == null) return 0;
        long since = clock.millis() - window.toMillis();
        synchronized (times) {
            return (int) times.stream().filter(time -> time >= since).count();
        }
    }

    /** Evita que el mapa crezca sin fin con claves viejas. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000)
    public void cleanUp() {
        long since = clock.millis() - Duration.ofDays(1).toMillis();
        hits.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty() || entry.getValue().peekLast() < since;
            }
        });
    }
}
