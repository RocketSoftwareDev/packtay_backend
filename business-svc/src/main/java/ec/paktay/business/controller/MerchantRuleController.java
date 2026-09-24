package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.MerchantRuleResponse;
import ec.paktay.business.dto.MoveMerchantRuleRequest;
import ec.paktay.business.service.MerchantRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/merchant-rules")
@Tag(name = "Usuario · Reglas por comercio", description = "Comercios que se asignan solos a una categoría")
@SecurityRequirement(name = "bearerAuth")
public class MerchantRuleController {
    private final MerchantRuleService rules;

    public MerchantRuleController(MerchantRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    @Operation(summary = "Listar mis reglas por comercio", description = "Ruta autenticada. Reglas activas, de la más usada a la menos. "
            + "Una regla nace al guardar una captura de Wallet desde Por revisar o al cambiar la categoría de un gasto de Wallet. "
            + "El teléfono guarda una copia para asignar sin conexión.")
    @ApiResponse(responseCode = "200", description = "Reglas del usuario")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public List<MerchantRuleResponse> list(@AuthenticationPrincipal Jwt jwt,
                                           @Parameter(description = "Sólo las reglas de esta categoría")
                                           @RequestParam(required = false) UUID categoryId) {
        return rules.list(UUID.fromString(jwt.getSubject()), categoryId);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Mover un comercio a otra categoría", description = "Ruta autenticada. Vale desde el próximo pago de ese comercio; "
            + "los gastos ya guardados se quedan en su categoría.")
    @ApiResponse(responseCode = "200", description = "Regla actualizada")
    @ApiResponse(responseCode = "400", description = "La categoría no existe, está inactiva o no es del usuario")
    @ApiResponse(responseCode = "404", description = "La regla no existe o no pertenece al usuario")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public MerchantRuleResponse move(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @Valid @RequestBody MoveMerchantRuleRequest request) {
        return rules.move(UUID.fromString(jwt.getSubject()), id, request.categoryId());
    }
}
