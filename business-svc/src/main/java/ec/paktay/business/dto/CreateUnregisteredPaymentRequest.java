package ec.paktay.business.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUnregisteredPaymentRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 180) String merchant,
        @NotBlank @Size(max = 120) String cardName,
        @Size(max = 255) String deviceId) {
}
