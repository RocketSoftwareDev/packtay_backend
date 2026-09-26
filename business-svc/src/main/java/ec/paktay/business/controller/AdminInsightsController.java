package ec.paktay.business.controller;

import java.util.List;

import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.admin.AdminInsights;
import ec.paktay.business.service.AdminInsightsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin · Resumen, notificaciones y estado", description = "Conteos agregados del panel; nunca montos. Requiere rol ADMIN.")
@SecurityRequirement(name = "bearerAuth")
public class AdminInsightsController {
    private final AdminInsightsService insights;

    public AdminInsightsController(AdminInsightsService insights) {
        this.insights = insights;
    }

    @GetMapping("/metrics/summary")
    @Operation(summary = "Cifras del Resumen", description = "Usuarios activos, altas, capturas de Wallet, tarjetas y avisos del mes calendario "
            + "(zona America/Guayaquil), usuarios por plan y altas de las últimas 8 semanas.")
    @ApiResponse(responseCode = "200", description = "Cifras")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    public AdminInsights.Metrics metrics() {
        return insights.metrics();
    }

    @GetMapping("/notifications/summary")
    @Operation(summary = "Resumen de avisos push", description = "Enviados, entregados y fallidos del mes, y dispositivos con avisos.")
    public AdminInsights.NotificationSummary notificationSummary() {
        return insights.notificationSummary();
    }

    @GetMapping("/notifications")
    @Operation(summary = "Registro de avisos push", description = "result: DELIVERED, FAILED o vacío. Últimos 200.")
    public List<AdminInsights.NotificationLog> notifications(@RequestParam(required = false) String result) {
        return insights.notifications(result);
    }

    @PostMapping("/notifications/test")
    @Operation(summary = "Enviar push de prueba", description = "Solo a los dispositivos del administrador que lo pide (tiene que haber "
            + "abierto la app con su cuenta y aceptado los avisos).")
    @ApiResponse(responseCode = "200", description = "Resultado del envío")
    public AdminInsights.TestPushResult testPush(@AuthenticationPrincipal Jwt jwt) {
        return insights.testPush(AdminActor.from(jwt));
    }

    @GetMapping("/system/status")
    @Operation(summary = "Estado del sistema", description = "Salud de auth-svc, Keycloak, la base y Firebase, tareas programadas, avisos de "
            + "configuración y versiones.")
    public AdminInsights.SystemStatus status() {
        return insights.systemStatus();
    }
}
