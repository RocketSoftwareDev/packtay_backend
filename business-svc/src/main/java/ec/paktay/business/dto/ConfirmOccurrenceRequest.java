package ec.paktay.business.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

public record ConfirmOccurrenceRequest(
        @Schema(description = "Lo que se cobró de verdad; null = el monto esperado", nullable = true)
        @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Schema(description = "true = usar este monto también en los próximos cobros («Usar $X desde ahora»)")
        boolean updateExpected) {
}
