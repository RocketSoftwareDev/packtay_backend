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
        @Schema(description = "Lo que cobró el banco, en currencyCode. Es lo que suma en los totales.")
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
        @Positive BigDecimal exchangeRateToUsd,
        @NotBlank @Size(max = 180) String merchant,
        @Schema(description = "Instante del consumo. En un gasto MANUAL no puede ser futuro ni de más de 7 días atrás (calendario del usuario).")
        @NotNull OffsetDateTime occurredAt,
        boolean recurring,
        Integer recurrenceDay,
        @Schema(description = "Opcional, false por defecto. true cuando el teléfono asignó tarjeta y categoría solo, sin que el usuario eligiera.")
        Boolean assignedByRule,
        @Schema(description = "MANUAL (formulario de la app, por defecto) o AUTOMATIC (captura de Wallet confirmada).",
                allowableValues = {"MANUAL", "AUTOMATIC"})
        @Pattern(regexp = "^(MANUAL|AUTOMATIC)$") String origin,
        @Schema(description = "Monto de la compra en su moneda original, sólo como referencia. Va junto con originalCurrencyCode.")
        @Positive BigDecimal originalAmount,
        @Pattern(regexp = "^[A-Z]{3}$") String originalCurrencyCode,
        @Schema(description = "País ISO 3166-1 alfa-2 donde estaba el teléfono al capturar, si el atajo lo mandó.")
        @Pattern(regexp = "^[A-Z]{2}$") String countryCode) {

    public boolean automatic() {
        return "AUTOMATIC".equals(origin);
    }
}
