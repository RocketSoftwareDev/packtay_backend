package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RecurringPaymentRequest(
        @NotBlank @Size(max = 180) String name,
        @Schema(description = "Monto esperado de cada cobro; al confirmarlo se puede corregir")
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull UUID cardId,
        @NotNull UUID categoryId,
        @Schema(allowableValues = {"MONTHLY", "YEARLY"})
        @NotNull @Pattern(regexp = "^(MONTHLY|YEARLY)$") String frequency,
        @Schema(description = "FIRST (primero del mes), DAY (dayOfMonth; si el mes no lo tiene, el último) o LAST (fin de mes)",
                allowableValues = {"FIRST", "DAY", "LAST"})
        @NotNull @Pattern(regexp = "^(FIRST|DAY|LAST)$") String dayRule,
        @Schema(description = "1 a 31, sólo con dayRule = DAY", nullable = true)
        @Min(1) @Max(31) Integer dayOfMonth,
        @Schema(description = "1 a 12, sólo con frequency = YEARLY", nullable = true)
        @Min(1) @Max(12) Integer monthOfYear,
        @Schema(description = "Último mes con cobro, YYYY-MM (inclusive); null = sin fecha de fin", example = "2027-09", nullable = true)
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String endMonth,
        @Schema(description = "true cuando se crea desde «Agregar gasto · Se repite»: el gasto de hoy ya se guardó, el primer cobro es desde mañana")
        boolean startsTomorrow) {
}
