package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record RecurringPaymentResponse(
        UUID id, String name, BigDecimal amount, String currencyCode,
        UUID cardId,
        @Schema(description = "Apodo de la tarjeta o, sin apodo, el nombre del banco")
        String cardName,
        String cardStatus,
        UUID categoryId, String categoryName,
        String frequency, String dayRule, Integer dayOfMonth, Integer monthOfYear,
        @Schema(description = "YYYY-MM o null", nullable = true)
        String endMonth,
        @Schema(allowableValues = {"ACTIVE", "PAUSED", "CANCELLED"})
        String status,
        @Schema(description = "Próximo cobro (ACTIVE); null si está pausado, cancelado o ya terminó", nullable = true)
        LocalDate nextDueDate,
        @Schema(description = "Lo que vale al mes: el monto, o el anual dividido en 12")
        BigDecimal monthlyEquivalent) {
}
