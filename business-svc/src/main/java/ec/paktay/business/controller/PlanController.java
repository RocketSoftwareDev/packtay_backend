package ec.paktay.business.controller;

import java.util.UUID;

import ec.paktay.business.dto.EntitlementsResponse;
import ec.paktay.business.service.BudgetService;
import ec.paktay.business.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/entitlements")
@Tag(name = "Usuario · Plan", description = "Plan del usuario, sus límites y el uso del mes")
@SecurityRequirement(name = "bearerAuth")
public class PlanController {
    private final PlanService plans;
    private final BudgetService budgets;

    public PlanController(PlanService plans, BudgetService budgets) {
        this.plans = plans;
        this.budgets = budgets;
    }

    @GetMapping
    @Transactional
    @Operation(summary = "Mi plan y sus límites", description = "Ruta autenticada. Plan Gratis: 2 tarjetas registradas, 20 capturas de Wallet al mes, "
            + "presupuesto en 3 categorías y 3 meses de historial. Pro y Duo sin límites (null). Sin suscripción registrada el usuario es PRO (beta). "
            + "La captura 21 de un usuario Gratis responde 409 en POST /user/expenses y el teléfono la deja bloqueada en Por revisar.")
    @ApiResponse(responseCode = "200", description = "Plan, límites y uso")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public EntitlementsResponse get(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return plans.entitlements(userId, () -> budgets.ensureCurrentPeriod(userId));
    }
}
