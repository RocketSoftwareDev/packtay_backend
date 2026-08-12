package ec.paktay.business.service;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {
    private final JdbcClient jdbc;

    public UserAccountService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void ensureActiveUser(UUID userId) {
        jdbc.sql("insert into app_users (id) values (:id) on conflict (id) do nothing")
                .param("id", userId).update();
        String status = jdbc.sql("select status from app_users where id = :id")
                .param("id", userId).query(String.class).single();
        if (!"ACTIVE".equals(status)) throw new IllegalArgumentException("La cuenta se encuentra desactivada");
        jdbc.sql("select create_user_system_categories(:id)").param("id", userId).query(Object.class).single();
    }
}
