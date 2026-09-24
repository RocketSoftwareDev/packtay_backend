package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateExpenseRequest(
        @Schema(description = "Categoría del gasto (obligatoria; activa y del usuario si cambia)")
        @NotNull UUID categoryId,
        @Schema(description = "Tarjeta del gasto (obligatoria; activa y del usuario si cambia)")
        @NotNull UUID cardId,
        @Schema(description = "Opcional. Sólo gastos MANUAL; en un AUTOMATIC se acepta únicamente si es igual al actual")
        @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Schema(description = "Opcional. Sólo gastos MANUAL; en un AUTOMATIC se acepta únicamente si es igual al actual")
        @Size(max = 180) String merchantRaw) {
}
