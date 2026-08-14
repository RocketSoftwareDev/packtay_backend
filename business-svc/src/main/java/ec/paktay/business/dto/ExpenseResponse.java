package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ExpenseResponse(UUID id, UUID cardId, String cardName, UUID categoryId, String categoryName,
                              String origin, BigDecimal amount, String currencyCode, String merchantRaw,
                              OffsetDateTime occurredAt, UUID installmentPlanId) {
}
