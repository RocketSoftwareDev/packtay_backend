package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CardResponse(UUID id, UUID bankId, String bankName, String alias, String last4,
                           String currencyCode, String status, BigDecimal currentPeriodBudget,
                           OffsetDateTime createdAt) {
}
