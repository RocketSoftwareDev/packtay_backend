package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record BudgetResponse(LocalDate periodMonth,
                             @Schema(description = "Monto por defecto de cada categoría seleccionada sin monto propio; no es un tope ni una bolsa", nullable = true)
                             BigDecimal globalAmount,
                             String currencyCode, String recurrence,
                             @Schema(description = "Gastos ACTIVE de tipo EXPENSE del mes en currencyCode (misma regla que spent del resumen)")
                             BigDecimal spentAmount,
                             List<CategoryBudgetResponse> categories,
                             @Schema(description = "Presupuesto del mes: suma de effectiveAmount de las categorías listadas. null si ninguna tiene monto", nullable = true)
                             BigDecimal budgetAmount,
                             @Schema(description = "budgetAmount - spentAmount; puede ser negativo; null sin presupuesto", nullable = true)
                             BigDecimal availableAmount,
                             @Schema(description = "spentAmount / budgetAmount * 100 redondeado hacia abajo; null sin presupuesto", nullable = true)
                             Integer percent) { }
