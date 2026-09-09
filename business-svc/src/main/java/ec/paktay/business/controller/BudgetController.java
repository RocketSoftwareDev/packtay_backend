package ec.paktay.business.controller;

import java.util.UUID;
import ec.paktay.business.dto.BudgetResponse;
import ec.paktay.business.dto.SaveBudgetRequest;
import ec.paktay.business.dto.UpdateCategoryBudgetRequest;
import ec.paktay.business.service.BudgetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user/budgets/current")
@Tag(name = "Usuario · Presupuestos", description = "Presupuesto global mensual y categorías seleccionadas")
@SecurityRequirement(name = "bearerAuth")
public class BudgetController {
    private final BudgetService budgets;
    public BudgetController(BudgetService budgets) { this.budgets = budgets; }

    @GetMapping
    @Operation(summary = "Consultar mi presupuesto actual", description = "Ruta autenticada. Devuelve el global y sólo las categorías seleccionadas. individualAmount nulo significa que esa categoría hereda globalAmount; cada categoría heredada recibe ese monto como su propio límite.")
    @ApiResponse(responseCode = "200", description = "Presupuesto mensual consultado")
    public BudgetResponse current(@AuthenticationPrincipal Jwt jwt) {
        return budgets.current(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping
    @Operation(summary = "Guardar plantilla global y presupuestos por categoría", description = "Ruta autenticada. globalAmount es una plantilla aplicada individualmente, nunca una bolsa ni una sumatoria. individualAmount nulo hereda la plantilla (o equivale a cero si está sin definir); un monto conserva el valor manual. recurrence acepta THIS_MONTH o MONTHLY.")
    @ApiResponse(responseCode = "200", description = "Presupuesto y selección guardados")
    @ApiResponse(responseCode = "400", description = "Categoría, moneda o monto inválido")
    public BudgetResponse save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveBudgetRequest request) {
        return budgets.save(UUID.fromString(jwt.getSubject()), request);
    }

    @PutMapping("/categories/{categoryId}")
    @Operation(summary = "Modificar o desactivar el presupuesto de una categoría", description = "Ruta autenticada. individualAmount nulo usa el global; active=false la quita de la selección sin borrar historial.")
    @ApiResponse(responseCode = "200", description = "Categoría presupuestaria actualizada")
    @ApiResponse(responseCode = "400", description = "Categoría ajena, inactiva o monto inválido")
    public BudgetResponse updateCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID categoryId,
                                         @Valid @RequestBody UpdateCategoryBudgetRequest request) {
        return budgets.updateCategory(UUID.fromString(jwt.getSubject()), categoryId, request);
    }
}
