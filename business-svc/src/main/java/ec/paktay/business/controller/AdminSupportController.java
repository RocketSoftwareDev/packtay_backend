package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.admin.AdminActor;
import ec.paktay.business.dto.support.SupportCounts;
import ec.paktay.business.dto.support.TicketDetail;
import ec.paktay.business.dto.support.TicketReplyRequest;
import ec.paktay.business.dto.support.TicketStatusRequest;
import ec.paktay.business.dto.support.TicketSummary;
import ec.paktay.business.service.SupportTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/admin/support/tickets")
@Tag(name = "Admin · Soporte", description = "Bandeja de tickets confirmados; requiere rol ADMIN. Los bloqueos están en auth-svc.")
@SecurityRequirement(name = "bearerAuth")
public class AdminSupportController {
    private final SupportTicketService tickets;

    public AdminSupportController(SupportTicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping
    @Operation(summary = "Listar tickets", description = "status: NEW, IN_PROGRESS o RESOLVED. q busca en correo, motivo y código.")
    @ApiResponse(responseCode = "200", description = "Tickets (máximo 200, más recientes primero)")
    @ApiResponse(responseCode = "403", description = "Se requiere el rol ADMIN")
    public List<TicketSummary> list(@RequestParam(defaultValue = "NEW") @Pattern(regexp = "NEW|IN_PROGRESS|RESOLVED") String status,
                                    @RequestParam(required = false) String q) {
        return tickets.list(status, q);
    }

    @GetMapping("/counts")
    @Operation(summary = "Contadores de la bandeja", description = "Tickets por estado, pendientes de confirmar y bloqueos.")
    @ApiResponse(responseCode = "200", description = "Contadores")
    public SupportCounts counts() {
        return tickets.counts();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Ver ticket", description = "Devuelve la conversación y lo marca como leído.")
    @ApiResponse(responseCode = "200", description = "Ticket con mensajes")
    @ApiResponse(responseCode = "404", description = "No existe o no está confirmado")
    public TicketDetail get(@PathVariable UUID id) {
        return tickets.get(id);
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "Responder o anotar", description = "kind REPLY envía el texto por correo y lo guarda; NOTE es una nota interna. "
            + "Responder deja el ticket IN_PROGRESS, o RESOLVED si resolve = true.")
    @ApiResponse(responseCode = "200", description = "Ticket actualizado")
    @ApiResponse(responseCode = "502", description = "El correo no salió; no se guardó nada")
    public TicketDetail reply(@PathVariable UUID id, @Valid @RequestBody TicketReplyRequest request, @AuthenticationPrincipal Jwt jwt) {
        return tickets.reply(id, request, AdminActor.from(jwt));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Cambiar estado del ticket")
    @ApiResponse(responseCode = "200", description = "Ticket actualizado")
    public TicketDetail setStatus(@PathVariable UUID id, @Valid @RequestBody TicketStatusRequest request) {
        return tickets.setStatus(id, request.status());
    }
}
