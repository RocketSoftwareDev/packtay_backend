package ec.paktay.business.controller;

import java.util.UUID;

import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.admin.AdminUserDetail;
import ec.paktay.business.dto.admin.AdminUserSummary;
import ec.paktay.business.dto.admin.ChangePlanRequest;
import ec.paktay.business.dto.admin.PageResponse;
import ec.paktay.business.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Admin · Usuarios (perfil y plan)", description = "Perfil, plan y uso del mes; nunca gastos ni montos. "
        + "Bloqueo, contraseña, borrado y roles están en auth-svc. Requiere rol ADMIN.")
@SecurityRequirement(name = "bearerAuth")
public class AdminUsersController {
    private final AdminUserService users;

    public AdminUsersController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "Listar usuarios", description = "q busca en correo y nombre. plan: FREE, PRO o DUO. status: ACTIVE o BLOCKED. "
            + "country: código de país. page empieza en 1; size máximo 50.")
    @ApiResponse(responseCode = "200", description = "Página de usuarios")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    public PageResponse<AdminUserSummary> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) String plan,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String country,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "8") int size) {
        return users.list(q, plan, status, country, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Ver usuario", description = "Perfil, plan, uso del mes (conteos), dispositivos y consentimientos.")
    @ApiResponse(responseCode = "200", description = "Detalle")
    @ApiResponse(responseCode = "404", description = "No existe")
    public AdminUserDetail get(@PathVariable UUID id) {
        return users.detail(id);
    }

    @PutMapping("/{id}/plan")
    @Operation(summary = "Cambiar plan", description = "Asigna el plan como TESTER. No cambia suscripciones de la tienda (409). Queda en Auditoría.")
    @ApiResponse(responseCode = "200", description = "Plan cambiado")
    @ApiResponse(responseCode = "409", description = "El plan viene de la tienda")
    public AdminUserDetail changePlan(@PathVariable UUID id, @Valid @RequestBody ChangePlanRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return users.changePlan(id, request.plan(), AdminActor.from(jwt));
    }
}
