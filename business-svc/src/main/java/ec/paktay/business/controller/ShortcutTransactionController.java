package ec.paktay.business.controller;

import java.util.UUID;
import java.util.List;

import ec.paktay.business.dto.ShortcutTransactionRequest;
import ec.paktay.business.dto.ShortcutTransactionResponse;
import ec.paktay.business.dto.PendingMovementResponse;
import ec.paktay.business.dto.ConfirmPendingMovementRequest;
import ec.paktay.business.dto.ExpenseResponse;
import ec.paktay.business.service.ExpenseService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final ExpenseService expenses;

    public ShortcutTransactionController(ShortcutTransactionService service, ExpenseService expenses) {
        this.service = service;
        this.expenses = expenses;
    }

    @PostMapping("/shortcut")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Recibir un consumo de Apple Shortcut", description = "Guarda un movimiento pendiente e idempotente. Nunca crea un gasto ni tarjeta hasta que el usuario lo confirme.")
    @ApiResponse(responseCode = "201", description = "Movimiento pendiente creado")
    @ApiResponse(responseCode = "400", description = "Datos de consumo inválidos")
    public ShortcutTransactionResponse ingest(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ShortcutTransactionRequest request) {
        return service.ingest(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping("/pending")
    @Operation(summary = "Listar consumos pendientes", description = "Ruta autenticada. Devuelve la cola persistida que todavía debe confirmar el usuario.")
    @ApiResponse(responseCode = "200", description = "Cola pendiente consultada")
    public List<PendingMovementResponse> listPending(@AuthenticationPrincipal Jwt jwt) {
        return service.listPending(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/{movementId}/confirm")
    @Operation(summary = "Confirmar un consumo como gasto", description = "Ruta autenticada. Crea el gasto y resuelve el movimiento en una única transacción. Repetir la confirmación devuelve el mismo gasto sin duplicarlo.")
    @ApiResponse(responseCode = "200", description = "Gasto confirmado y persistido")
    @ApiResponse(responseCode = "400", description = "Movimiento, tarjeta, categoría o recurrencia inválidos")
    public ExpenseResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID movementId,
                                   @Valid @RequestBody ConfirmPendingMovementRequest request) {
        return expenses.confirm(UUID.fromString(jwt.getSubject()), movementId, request);
    }

    @DeleteMapping("/{movementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Descartar un consumo pendiente", description = "Ruta autenticada. Conserva el evento para auditoría y cambia su estado a DISCARDED; no crea un gasto.")
    @ApiResponse(responseCode = "204", description = "Movimiento descartado")
    @ApiResponse(responseCode = "400", description = "Movimiento inexistente o ya resuelto")
    public void discard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID movementId) {
        service.discard(UUID.fromString(jwt.getSubject()), movementId);
    }
}
