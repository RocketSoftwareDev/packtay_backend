package ec.paktay.business.controller;

import java.util.UUID;

import ec.paktay.business.dto.ShortcutTransactionRequest;
import ec.paktay.business.dto.ShortcutTransactionResponse;
import ec.paktay.business.service.ShortcutTransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/movements")
@Tag(name = "Usuario · Ingesta de consumos")
@SecurityRequirement(name = "bearerAuth")
public class ShortcutTransactionController {
    private final ShortcutTransactionService service;

    public ShortcutTransactionController(ShortcutTransactionService service) { this.service = service; }

    @PostMapping("/shortcut")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Recibir un consumo de Apple Shortcut", description = "Guarda un movimiento pendiente e idempotente. Nunca crea un gasto ni tarjeta hasta que el usuario lo confirme.")
    @ApiResponse(responseCode = "201", description = "Movimiento pendiente creado")
    @ApiResponse(responseCode = "400", description = "Datos de consumo inválidos")
    public ShortcutTransactionResponse ingest(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ShortcutTransactionRequest request) {
        return service.ingest(UUID.fromString(jwt.getSubject()), request);
    }
}
