package ec.paktay.business.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import ec.paktay.business.dto.CreateExpenseRequest;
import ec.paktay.business.dto.ExpensePageResponse;
import ec.paktay.business.dto.ExpenseResponse;
import ec.paktay.business.dto.UpdateExpenseRequest;
import ec.paktay.business.dto.VoidExpenseResponse;
import ec.paktay.business.service.ExpenseQueryService;
import ec.paktay.business.service.ExpenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/expenses")
@Tag(name = "Usuario · Gastos", description = "Registro, historial, edición y anulación de gastos del usuario autenticado")
@SecurityRequirement(name = "bearerAuth")
public class ExpenseController {
    private final ExpenseQueryService expenses;
    private final ExpenseService expenseWriter;

    public ExpenseController(ExpenseQueryService expenses, ExpenseService expenseWriter) {
        this.expenses = expenses;
        this.expenseWriter = expenseWriter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar un gasto manual", description = "Ruta autenticada. Persiste el gasto en PostgreSQL. idempotencyKey permite que el móvil reintente sin crear duplicados: un reintento, aunque llegue en paralelo, devuelve el mismo gasto con el mismo cuerpo. assignedByRule (opcional, false por defecto) marca las capturas cuya tarjeta o categoría asignó una regla del teléfono.")
    @ApiResponse(responseCode = "201", description = "Gasto creado o recuperado por idempotencia")
    @ApiResponse(responseCode = "400", description = "Tarjeta, categoría, moneda, monto o recurrencia inválidos")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public ExpenseResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateExpenseRequest request) {
        return expenseWriter.createManual(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    @Operation(summary = "Listar mis gastos (paginado)", description = "Ruta autenticada. Devuelve { items, nextCursor }. "
            + "Sin since: todo el historial del usuario (incluidos gastos anulados, registros REFUND y gastos de tarjetas desactivadas o eliminadas) "
            + "ordenado por occurredAt desc, id desc. from/to son días del calendario del usuario, interpretados en la zona horaria de su perfil. "
            + "Con since: sólo filas con updatedAt >= since, ordenadas por updatedAt asc, id asc, para la sincronización incremental del teléfono; "
            + "incluye VOIDED y REFUND. Paginación por clave: pasar nextCursor en cursor, con los mismos filtros y el mismo since; "
            + "nextCursor null indica la última página. Un cursor de la consulta sin since no vale con since y viceversa.")
    @ApiResponse(responseCode = "200", description = "Página del historial")
    @ApiResponse(responseCode = "400", description = "Fechas invertidas, limit fuera de 1..200, cursor inválido o parámetro mal formado")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public ExpensePageResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Fecha inicial inclusiva, formato yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha final inclusiva, formato yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Filtra por tarjeta")
            @RequestParam(required = false) UUID cardId,
            @Parameter(description = "Filtra por categoría")
            @RequestParam(required = false) UUID categoryId,
            @Parameter(description = "Tamaño de página, 1 a 200; 50 por defecto")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "nextCursor de la página anterior; opaco, no se interpreta en el cliente")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Instante ISO-8601 (por ejemplo 2026-09-24T15:00:00Z). Activa la sincronización incremental por updatedAt")
            @RequestParam(required = false) Instant since) {
        return expenses.list(UUID.fromString(jwt.getSubject()), from, to, cardId, categoryId, limit, cursor, since);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar un gasto", description = "Ruta autenticada. Devuelve el gasto si es del usuario, en cualquier estado (ACTIVE, VOIDED) y tipo (EXPENSE, REFUND).")
    @ApiResponse(responseCode = "200", description = "Gasto encontrado")
    @ApiResponse(responseCode = "404", description = "El gasto no existe o no pertenece al usuario")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public ExpenseResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return expenses.get(UUID.fromString(jwt.getSubject()), id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Editar un gasto", description = "Ruta autenticada. categoryId y cardId son obligatorios y siempre editables; "
            + "si cambian, deben ser del usuario y estar activos. amount y merchantRaw son opcionales y sólo se pueden cambiar en gastos MANUAL: "
            + "en un gasto AUTOMATIC (Wallet) se aceptan únicamente si coinciden con el valor actual. Sólo se editan gastos ACTIVE de tipo EXPENSE. "
            + "Editar no modifica las reglas ni las sugerencias de categoría. Sin cambios reales devuelve el gasto tal cual.")
    @ApiResponse(responseCode = "200", description = "Gasto actualizado")
    @ApiResponse(responseCode = "400", description = "Validación fallida, tarjeta o categoría inválida, o intento de cambiar monto/comercio de un gasto de Wallet")
    @ApiResponse(responseCode = "404", description = "El gasto no existe o no pertenece al usuario")
    @ApiResponse(responseCode = "409", description = "El gasto está anulado, es un registro REFUND o pertenece a un período cerrado")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public ExpenseResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody UpdateExpenseRequest request) {
        return expenseWriter.update(UUID.fromString(jwt.getSubject()), id, request);
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "Anular un gasto", description = "Ruta autenticada. El gasto pasa a VOIDED y deja de contar; se crea un registro REFUND ACTIVE "
            + "con el mismo monto (positivo), tarjeta, categoría, moneda, origen y fecha del original, y comercio \"Anulación: <comercio>\". "
            + "Idempotente: anular un gasto ya anulado devuelve el mismo par sin crear otro registro. Funciona aunque la tarjeta ya esté inactiva o eliminada.")
    @ApiResponse(responseCode = "200", description = "Gasto anulado (o ya anulado) y su registro compensatorio")
    @ApiResponse(responseCode = "404", description = "El gasto no existe o no pertenece al usuario")
    @ApiResponse(responseCode = "409", description = "Es un registro REFUND o pertenece a un período cerrado")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public VoidExpenseResponse voidExpense(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return expenseWriter.voidExpense(UUID.fromString(jwt.getSubject()), id);
    }
}
