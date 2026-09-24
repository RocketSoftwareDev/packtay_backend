package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record CategoryBudgetResponse(UUID categoryId, String alias, String icon, String colorDark,
                                     String colorLight,
                                     @Schema(description = "Monto propio; null = usa globalAmount", nullable = true)
                                     BigDecimal individualAmount,
                                     boolean active,
                                     @Schema(description = "Gastos ACTIVE de tipo EXPENSE del mes en esta categoría, en la moneda del presupuesto")
                                     BigDecimal spentAmount,
                                     @Schema(description = "individualAmount si existe; si no, globalAmount; null si ninguno", nullable = true)
                                     BigDecimal effectiveAmount,
                                     @Schema(description = "OWN, GLOBAL o null", allowableValues = {"OWN", "GLOBAL"}, nullable = true)
                                     String budgetSource,
                                     @Schema(description = "spentAmount / effectiveAmount * 100 redondeado hacia abajo; null sin monto", nullable = true)
                                     Integer percent,
                                     @Schema(allowableValues = {"NO_BUDGET", "OK", "AT_LIMIT", "OVER"})
                                     String status) { }
