package ec.paktay.business.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Resumen del mes para la pantalla de inicio (GET /api/v1/user/summary). Lo arma SummaryService con SummaryMath. */
public record MonthSummaryResponse(
        @Schema(description = "Mes consultado, YYYY-MM", example = "2026-09")
        String month,
        @Schema(description = "Zona horaria IANA del usuario con la que se calcularon los límites del mes", example = "America/Guayaquil")
        String timezone,
        @Schema(description = "Moneda del presupuesto; spent sólo suma gastos en esta moneda", example = "USD")
        String currency,
        int daysInMonth,
        @Schema(description = "Día de hoy en la zona del usuario; en un mes pasado es igual a daysInMonth")
        int dayOfMonth,
        @Schema(description = "Días que quedan contando hoy (daysInMonth - dayOfMonth + 1); 0 en un mes pasado")
        int daysLeft,
        @Schema(description = "Primer día del mes siguiente, cuando el resumen vuelve a cero", example = "2026-10-01")
        LocalDate resetsOn,
        @Schema(description = "Suma del presupuesto efectivo de cada categoría activa seleccionada (monto propio o, si no tiene, el global). null si no hay presupuesto", nullable = true)
        BigDecimal budget,
        @Schema(description = "Gastos ACTIVE de tipo EXPENSE del mes en la moneda del presupuesto")
        BigDecimal spent,
        @Schema(description = "budget - spent; puede ser negativo; null sin presupuesto", nullable = true)
        BigDecimal available,
        @Schema(description = "spent / budget * 100 redondeado hacia abajo; null sin presupuesto", nullable = true)
        Integer percent,
        @Schema(description = "dayOfMonth / daysInMonth * 100 redondeado hacia abajo")
        int elapsedPercent,
        @Schema(description = "NONE sin presupuesto; OVER si spent > budget; FAST si percent > elapsedPercent + 10; OK en el resto",
                allowableValues = {"NONE", "OK", "FAST", "OVER"})
        String pace,
        @Schema(description = "max(spent - budget, 0)")
        BigDecimal overBy,
        @Schema(description = "Gastos del mes en otra moneda que no suman en spent")
        int otherCurrencyCount,
        @Schema(description = "Categorías con gasto en el mes o seleccionadas en el presupuesto, por spent desc y nombre")
        List<SummaryCategoryResponse> categories,
        @Schema(description = "Tarjetas activas y tarjetas inactivas o eliminadas con gasto en el mes; activas primero, luego spent desc")
        List<SummaryCardResponse> cards,
        @Schema(description = "Los 3 gastos ACTIVE de tipo EXPENSE más recientes del mes")
        List<ExpenseResponse> recent,
        SummaryCountsResponse counts) {
}
