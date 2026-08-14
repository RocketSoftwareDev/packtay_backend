package ec.paktay.business.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.ShortcutTransactionRequest;
import ec.paktay.business.dto.ShortcutTransactionResponse;
import ec.paktay.business.dto.PendingMovementResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShortcutTransactionService {
    private final JdbcClient jdbc;
    private final UserAccountService users;

    public ShortcutTransactionService(JdbcClient jdbc, UserAccountService users) { this.jdbc = jdbc; this.users = users; }

    @Transactional
    public ShortcutTransactionResponse ingest(UUID userId, ShortcutTransactionRequest request) {
        users.ensureActiveUser(userId);
        ShortcutTransactionResponse duplicate = jdbc.sql("""
                select id, suggested_card_id, suggested_category_id
                  from pending_movements where user_id = :userId and idempotency_key = :key
                """).param("userId", userId).param("key", request.idempotencyKey())
                .query((rs, rowNum) -> new ShortcutTransactionResponse(rs.getObject("id", UUID.class),
                        rs.getObject("suggested_card_id", UUID.class), rs.getObject("suggested_category_id", UUID.class), true))
                .optional().orElse(null);
        if (duplicate != null) return duplicate;

        boolean validBank = jdbc.sql("select exists(select 1 from banks where id = :id and active)")
                .param("id", request.bankId()).query(Boolean.class).single();
        if (!validBank) throw new IllegalArgumentException("El banco seleccionado no existe o está inactivo");
        boolean validCurrency = jdbc.sql("select exists(select 1 from currencies where code = :code and active)")
                .param("code", request.currencyCode()).query(Boolean.class).single();
        if (!validCurrency) throw new IllegalArgumentException("La moneda seleccionada no existe o está inactiva");

        String normalized = normalize(request.merchantRaw());
        UUID suggestedCard = jdbc.sql("""
                select id from cards
                 where user_id = :userId and bank_id = :bankId and last4 = :last4 and status = 'ACTIVE'
                """).param("userId", userId).param("bankId", request.bankId()).param("last4", request.cardLast4())
                .query(UUID.class).optional().orElse(null);
        UUID suggestedCategory = jdbc.sql("""
                select category_id from user_consumption_selections
                 where user_id = :userId and merchant_normalized = :merchant and normalization_version = 1 and active
                """).param("userId", userId).param("merchant", normalized).query(UUID.class).optional().orElse(null);

        UUID pendingId = jdbc.sql("""
                insert into pending_movements (
                    user_id, idempotency_key, source, raw_payload, raw_text, parsed_amount, parsed_currency_code,
                    merchant_raw, merchant_normalized, normalization_version, bank_id, last4, occurred_at,
                    suggested_card_id, suggested_category_id)
                values (:userId, :key, 'IOS_SHORTCUT', cast(:rawPayload as jsonb), :rawText, :amount, :currency,
                    :merchantRaw, :merchantNormalized, 1, :bankId, :last4, :occurredAt, :suggestedCard, :suggestedCategory)
                returning id
                """).param("userId", userId).param("key", request.idempotencyKey()).param("rawPayload", request.rawPayload())
                .param("rawText", request.rawText()).param("amount", request.amount()).param("currency", request.currencyCode())
                .param("merchantRaw", request.merchantRaw().trim()).param("merchantNormalized", normalized)
                .param("bankId", request.bankId()).param("last4", request.cardLast4()).param("occurredAt", request.occurredAt())
                .param("suggestedCard", suggestedCard).param("suggestedCategory", suggestedCategory)
                .query(UUID.class).single();
        return new ShortcutTransactionResponse(pendingId, suggestedCard, suggestedCategory, false);
    }

    @Transactional
    public List<PendingMovementResponse> listPending(UUID userId) {
        users.ensureActiveUser(userId);
        return jdbc.sql("""
                select id, idempotency_key, source, parsed_amount, parsed_currency_code, merchant_raw,
                       bank_id, last4, occurred_at, suggested_card_id, suggested_category_id,
                       status::text, created_at
                  from pending_movements
                 where user_id = :userId and status = 'PENDING'
                 order by created_at desc
                """).param("userId", userId).query((rs, rowNum) -> new PendingMovementResponse(
                        rs.getObject("id", UUID.class), rs.getObject("idempotency_key", UUID.class),
                        rs.getString("source"), rs.getBigDecimal("parsed_amount"), rs.getString("parsed_currency_code"),
                        rs.getString("merchant_raw"), rs.getObject("bank_id", UUID.class), rs.getString("last4"),
                        rs.getObject("occurred_at", java.time.OffsetDateTime.class),
                        rs.getObject("suggested_card_id", UUID.class), rs.getObject("suggested_category_id", UUID.class),
                        rs.getString("status"), rs.getObject("created_at", java.time.OffsetDateTime.class))).list();
    }

    @Transactional
    public void discard(UUID userId, UUID movementId) {
        users.ensureActiveUser(userId);
        int updated = jdbc.sql("""
                update pending_movements set status = 'DISCARDED', resolved_at = now()
                 where id = :id and user_id = :userId and status = 'PENDING'
                """).param("id", movementId).param("userId", userId).update();
        if (updated == 0) throw new IllegalArgumentException("El movimiento no existe o ya fue resuelto");
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
