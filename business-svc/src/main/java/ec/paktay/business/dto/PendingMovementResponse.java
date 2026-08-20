package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PendingMovementResponse(
        UUID id, UUID idempotencyKey, String source, BigDecimal amount, String currencyCode,
        String merchant, UUID bankId, String cardLast4, String cardName, OffsetDateTime occurredAt,
        UUID suggestedCardId, UUID suggestedCategoryId, String status, OffsetDateTime createdAt) {
}
