package ec.paktay.business.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.CardResponse;
import ec.paktay.business.dto.CreateCardRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardService {
    private final JdbcClient jdbc;

    public CardService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public CardResponse register(UUID userId, String displayName, String email, CreateCardRequest request) {
        ensureProfile(userId, displayName, email);
        Integer bankExists = jdbc.sql("select count(*) from banks where id = :bankId and active")
                .param("bankId", request.bankId()).query(Integer.class).single();
        if (bankExists == 0) throw new IllegalArgumentException("El banco seleccionado no existe o está inactivo");

        UUID accountId = jdbc.sql("select id from bank_accounts where user_id = :userId and bank_id = :bankId")
                .param("userId", userId).param("bankId", request.bankId()).query(UUID.class).optional()
                .orElseGet(() -> jdbc.sql("insert into bank_accounts (user_id, bank_id) values (:userId, :bankId) returning id")
                        .param("userId", userId).param("bankId", request.bankId()).query(UUID.class).single());
        try {
            return jdbc.sql("""
                    insert into cards (bank_account_id, brand, kind, last4, nickname, auto_detected)
                    values (:accountId, cast(:brand as card_brand), cast(:kind as card_kind), :last4, :nickname, false)
                    returning id, :bankId as bank_id, (select short_name from banks where id = :bankId) as bank_name,
                              brand::text, kind::text, last4, nickname, active, created_at
                    """).param("accountId", accountId).param("bankId", request.bankId()).param("brand", request.brand())
                    .param("kind", request.kind()).param("last4", request.last4()).param("nickname", request.nickname())
                    .query(this::mapCard).single();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Esta tarjeta ya está registrada para ese banco");
        }
    }

    public List<CardResponse> list(UUID userId) {
        return jdbc.sql("""
                select c.id, b.id as bank_id, b.short_name as bank_name, c.brand::text, c.kind::text,
                       c.last4, c.nickname, c.active, c.created_at
                  from cards c join bank_accounts ba on ba.id = c.bank_account_id join banks b on b.id = ba.bank_id
                 where ba.user_id = :userId order by c.created_at desc
                """).param("userId", userId).query(this::mapCard).list();
    }

    private void ensureProfile(UUID userId, String displayName, String email) {
        String resolvedName = displayName == null || displayName.isBlank() ? email : displayName;
        jdbc.sql("insert into profiles (id, display_name, email) values (:id, :name, :email) on conflict (id) do nothing")
                .param("id", userId).param("name", resolvedName).param("email", email).update();
        jdbc.sql("insert into user_settings (user_id) values (:id) on conflict (user_id) do nothing")
                .param("id", userId).update();
    }

    private CardResponse mapCard(ResultSet rs, int rowNum) throws SQLException {
        return new CardResponse(rs.getObject("id", UUID.class), rs.getObject("bank_id", UUID.class), rs.getString("bank_name"),
                rs.getString("brand"), rs.getString("kind"), rs.getString("last4"), rs.getString("nickname"),
                rs.getBoolean("active"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
