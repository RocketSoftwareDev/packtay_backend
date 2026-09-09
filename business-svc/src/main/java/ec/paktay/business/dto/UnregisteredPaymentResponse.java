package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record UnregisteredPaymentResponse(
        long id,
        BigDecimal amount,
        String merchant,
        String cardName,
        String deviceId,
        OffsetDateTime createdAt) {
}
