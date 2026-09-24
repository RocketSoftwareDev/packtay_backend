package ec.paktay.business.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {
    /** Zona por defecto de app_users.timezone (V4). */
    public static final String DEFAULT_TIMEZONE = "America/Guayaquil";

    private final JdbcClient jdbc;

    public UserAccountService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Crea app_users en la primera petición del usuario. Una cuenta eliminada
     * (deleted_accounts, V6) no se vuelve a crear aunque su token siga vigente.
     */
    @Transactional
    public void ensureActiveUser(UUID userId) {
        jdbc.sql("""
                insert into app_users (id)
                select :id where not exists (select 1 from deleted_accounts where user_id = :id)
                on conflict (id) do nothing
                """).param("id", userId).update();
        String status = jdbc.sql("select status from app_users where id = :id")
                .param("id", userId).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("La cuenta fue eliminada"));
        if (!"ACTIVE".equals(status)) throw new IllegalArgumentException("La cuenta se encuentra desactivada");
    }

    /**
     * Zona horaria del usuario (app_users.timezone). Si la fila no existe o guarda
     * una zona que Java no reconoce, usa {@link #DEFAULT_TIMEZONE}.
     */
    public ZoneId zoneOf(UUID userId) {
        String timezone = jdbc.sql("select timezone from app_users where id = :id")
                .param("id", userId).query(String.class).optional().orElse(DEFAULT_TIMEZONE);
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException ex) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }
}
