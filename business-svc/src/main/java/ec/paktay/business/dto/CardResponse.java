package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CardResponse(UUID id, UUID bankId, String bankName, String bankLogoUrl,
                           String cardType, String creditBrand, String name, String last4, String colorDark, String colorLight,
                           String currencyCode, String status, BigDecimal currentPeriodBudget,
                           OffsetDateTime createdAt) {
}
