package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateExpenseRequest(
        @NotNull UUID idempotencyKey,
        @NotNull UUID cardId,
        @NotNull UUID categoryId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
        @Positive BigDecimal exchangeRateToUsd,
        @NotBlank @Size(max = 180) String merchant,
        @NotNull OffsetDateTime occurredAt,
        boolean recurring,
        Integer recurrenceDay,
        @Schema(description = "Opcional, false por defecto. true cuando el teléfono asignó tarjeta o categoría por una regla.")
        Boolean assignedByRule) {
}
