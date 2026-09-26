package ec.paktay.auth.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.auth.dto.BlockRequest;
import ec.paktay.auth.dto.BlockResponse;
import ec.paktay.auth.dto.MessageResponse;
import ec.paktay.auth.service.AdminActor;
import ec.paktay.auth.service.IdentityBlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/blocks")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin · Bloqueos", description = "Correos, dominios e IP bloqueados para soporte, registro y cuenta. Requiere rol ADMIN.")
@SecurityRequirement(name = "bearerAuth")
public class AdminBlockController {
    private final IdentityBlockService blocks;

    public AdminBlockController(IdentityBlockService blocks) {
        this.blocks = blocks;
    }

    @GetMapping
    @Operation(summary = "Listar bloqueos", description = "type: EMAIL, DOMAIN, IP o vacío. q busca en el valor.")
    @ApiResponse(responseCode = "200", description = "Bloqueos (máximo 500)")
    public List<BlockResponse> list(@RequestParam(required = false) String type, @RequestParam(required = false) String q) {
        return blocks.list(type, q);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear bloqueo", description = "Correos normalizados (sin alias + ni puntos en Gmail). Un dominio público "
            + "(gmail.com, hotmail.com…) se rechaza. Con ACCOUNT, la cuenta de ese correo se bloquea y se cierran sus sesiones. "
            + "alsoBlockIp bloquea también la IP del último ticket de ese correo.")
    @ApiResponse(responseCode = "201", description = "Bloqueo creado")
    @ApiResponse(responseCode = "400", description = "Valor inválido o dominio público")
    @ApiResponse(responseCode = "409", description = "Ya estaba bloqueado o es tu propia cuenta")
    public BlockResponse create(@Valid @RequestBody BlockRequest request, @AuthenticationPrincipal Jwt jwt) {
        String ip = request.alsoBlockIp() && "EMAIL".equals(request.type()) ? blocks.lastTicketIp(request.value()) : null;
        return blocks.create(request, AdminActor.from(jwt), ip);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Quitar bloqueo", description = "Si el bloqueo incluía ACCOUNT, la cuenta también se desbloquea.")
    @ApiResponse(responseCode = "200", description = "Bloqueo quitado")
    @ApiResponse(responseCode = "404", description = "No existe")
    public MessageResponse delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        blocks.delete(id, AdminActor.from(jwt));
        return new MessageResponse("Bloqueo quitado");
    }
}
