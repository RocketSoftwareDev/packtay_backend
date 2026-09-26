package ec.paktay.business.controller;

import ec.paktay.business.dto.admin.AdminAuditPage;
import ec.paktay.business.service.AdminAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "Admin · Auditoría", description = "Altas de usuario, acciones del administrador y cuentas eliminadas (90 días). Requiere rol ADMIN.")
@SecurityRequirement(name = "bearerAuth")
public class AdminAuditController {
    private final AdminAuditService audit;

    public AdminAuditController(AdminAuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    @Operation(summary = "Listar eventos", description = "kind: USER_CREATED, ADMIN_ACTION o ACCOUNT_DELETED (vacío = todos). "
            + "q busca en el sujeto y el actor. days: 1 a 90.")
    @ApiResponse(responseCode = "200", description = "Eventos (máximo 200) y conteo por tipo")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    public AdminAuditPage list(@RequestParam(required = false) String kind,
                               @RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "30") int days) {
        return audit.list(kind, q, days);
    }
}
