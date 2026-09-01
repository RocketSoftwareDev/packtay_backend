package ec.paktay.business.service;

import java.text.Normalizer;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.paktay.business.dto.ShortcutPaymentRequest;
import ec.paktay.business.dto.ShortcutTransactionResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticatedShortcutPaymentService {
    private final JdbcClient jdbc;
    private final UserAccountService users;
    private final ObjectMapper json;

    public AuthenticatedShortcutPaymentService(JdbcClient jdbc, UserAccountService users, ObjectMapper json) {
        this.jdbc = jdbc;
        this.users = users;
        this.json = json;
    }

    @Transactional
    public ShortcutTransactionResponse ingest(UUID userId, ShortcutPaymentRequest request) {
        users.ensureActiveUser(userId);
        BigDecimal amount = parseAmount(request.amount());
        ShortcutTransactionResponse duplicate = jdbc.sql("""
                select id, suggested_card_id, suggested_category_id from pending_movements
                 where user_id = :userId and idempotency_key = :key
                """).param("userId", userId).param("key", request.eventId())
                .query((rs, rowNum) -> new ShortcutTransactionResponse(rs.getObject("id", UUID.class),
                        rs.getObject("suggested_card_id", UUID.class),
                        rs.getObject("suggested_category_id", UUID.class), true))
                .optional().orElse(null);
        if (duplicate != null) return duplicate;

        CardMatch card = jdbc.sql("""
                select id, bank_id from cards
                 where user_id = :userId and lower(btrim(name)) = lower(btrim(:cardName)) and status = 'ACTIVE'
                 order by created_at desc limit 1
                """).param("userId", userId).param("cardName", request.cardName().trim())
                .query((rs, rowNum) -> new CardMatch(rs.getObject("id", UUID.class), rs.getObject("bank_id", UUID.class)))
                .optional().orElse(null);
        String merchantNormalized = normalize(request.merchant());
        UUID category = jdbc.sql("""
                select category_id from user_consumption_selections
                 where user_id = :userId and merchant_normalized = :merchant
                   and normalization_version = 1 and active
                """).param("userId", userId).param("merchant", merchantNormalized)
                .query(UUID.class).optional().orElse(null);

        String rawPayload;
        try {
            rawPayload = json.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("No se pudo serializar el pago recibido");
        }

        UUID pendingId = jdbc.sql("""
                insert into pending_movements (
                    user_id, idempotency_key, source, raw_payload, parsed_amount,
                    parsed_currency_code, merchant_raw, merchant_normalized,
                    normalization_version, bank_id, card_name, occurred_at,
                    suggested_card_id, suggested_category_id)
                values (:userId, :key, 'IOS_SHORTCUT', cast(:payload as jsonb), :amount,
                    'USD', :merchant, :normalized, 1, :bankId, :cardName, :occurredAt,
                    :cardId, :categoryId)
                returning id
                """).param("userId", userId).param("key", request.eventId())
                .param("payload", rawPayload).param("amount", amount)
                .param("merchant", request.merchant().trim()).param("normalized", merchantNormalized)
                .param("bankId", card == null ? null : card.bankId(), java.sql.Types.OTHER)
                .param("cardName", request.cardName().trim()).param("occurredAt", request.occurredAt())
                .param("cardId", card == null ? null : card.id(), java.sql.Types.OTHER)
                .param("categoryId", category, java.sql.Types.OTHER).query(UUID.class).single();
        return new ShortcutTransactionResponse(pendingId, card == null ? null : card.id(), category, false);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal parseAmount(String raw) {
        String value = raw.replaceAll("[^0-9,.-]", "");
        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma > dot) {
            value = value.replace(".", "").replace(',', '.');
        } else {
            value = value.replace(",", "");
        }
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() <= 0) throw new NumberFormatException();
            return amount;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("El monto enviado por Wallet no es válido");
        }
    }

    private record CardMatch(UUID id, UUID bankId) {}
}
