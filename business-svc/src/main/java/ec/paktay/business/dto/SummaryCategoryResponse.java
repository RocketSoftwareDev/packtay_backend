package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Categoría dentro del resumen del mes. */
public record SummaryCategoryResponse(
        UUID categoryId,
        @Schema(description = "Nombre visible (alias de la categoría del usuario)")
        String name,
        String icon, String colorDark, String colorLight,
        @Schema(description = "Presupuesto efectivo: monto propio o, si no tiene, el global. null si la categoría no está seleccionada o no hay monto", nullable = true)
        BigDecimal budget,
        @Schema(description = "OWN (monto propio), GLOBAL (hereda el global) o null", allowableValues = {"OWN", "GLOBAL"}, nullable = true)
        String budgetSource,
        BigDecimal spent,
        @Schema(description = "spent / budget * 100 redondeado hacia abajo; null sin presupuesto", nullable = true)
        Integer percent,
        @Schema(description = "max(spent - budget, 0)")
        BigDecimal overBy,
        @Schema(description = "NO_BUDGET sin presupuesto; OVER si spent > budget; AT_LIMIT desde 90 % (incluye 100 %); OK en el resto",
                allowableValues = {"NO_BUDGET", "OK", "AT_LIMIT", "OVER"})
        String status) {
}
