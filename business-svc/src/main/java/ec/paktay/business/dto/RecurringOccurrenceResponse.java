package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Un cobro de un pago recurrente pendiente de confirmar («Por revisar»). */
public record RecurringOccurrenceResponse(
        UUID id, UUID recurringPaymentId, String name,
        LocalDate dueDate, BigDecimal expectedAmount, String currencyCode,
        UUID cardId, String cardName, String cardStatus,
        UUID categoryId, String categoryName,
        @Schema(allowableValues = {"PENDING", "CONFIRMED", "SKIPPED", "CANCELLED"})
        String status,
        @Schema(description = "Cada cuánto se cobra, para el texto «recurrente cada 15»")
        String frequency, String dayRule, Integer dayOfMonth, Integer monthOfYear) {
}
