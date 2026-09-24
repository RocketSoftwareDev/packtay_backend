package ec.paktay.business.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

public record CardLimitRequest(
        @Schema(description = "Límite mensual en la moneda de la tarjeta; null lo quita", nullable = true)
        @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount) {
}
