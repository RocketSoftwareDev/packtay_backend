package ec.paktay.business.controller;

import java.util.UUID;

import ec.paktay.business.dto.MonthSummaryResponse;
import ec.paktay.business.service.SummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/summary")
@Tag(name = "Usuario · Resumen", description = "Resumen del mes para la pantalla de inicio")
@SecurityRequirement(name = "bearerAuth")
public class SummaryController {
    private final SummaryService summaries;

    public SummaryController(SummaryService summaries) { this.summaries = summaries; }

    @GetMapping
    @Operation(summary = "Resumen del mes", description = "Ruta autenticada. Mes calendario en la zona horaria del perfil; vuelve a cero el día 1. "
            + "budget = suma del presupuesto efectivo de cada categoría activa seleccionada (su monto propio o, si no tiene, el global: "
            + "el global es un monto por categoría, no un tope). spent = gastos ACTIVE de tipo EXPENSE del mes en la moneda del presupuesto; "
            + "los de otra moneda sólo se cuentan en otherCurrencyCount. Porcentajes redondeados hacia abajo. daysLeft cuenta hoy. "
            + "pace: NONE sin presupuesto, OVER si spent > budget, FAST si percent > elapsedPercent + 10, OK en el resto. "
            + "Categorías: status NO_BUDGET, OVER, AT_LIMIT (desde 90 %, incluye 100 %) u OK. Tarjetas: todas las activas más las inactivas o "
            + "eliminadas con gasto en el mes; ownLimit es su límite propio del mes, sólo informativo. recent: los 3 gastos más recientes del mes. "
            + "Un mes pasado devuelve daysLeft = 0 y dayOfMonth = daysInMonth.")
    @ApiResponse(responseCode = "200", description = "Resumen del mes")
    @ApiResponse(responseCode = "400", description = "month mal formado (no es YYYY-MM) o es un mes futuro")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public MonthSummaryResponse summary(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Mes YYYY-MM; por defecto el mes actual en la zona horaria del usuario", example = "2026-09")
            @RequestParam(required = false) String month) {
        return summaries.summary(UUID.fromString(jwt.getSubject()), month);
    }
}
