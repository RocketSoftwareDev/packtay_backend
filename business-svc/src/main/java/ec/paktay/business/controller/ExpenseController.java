package ec.paktay.business.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.ExpenseResponse;
import ec.paktay.business.dto.CreateExpenseRequest;
import ec.paktay.business.service.ExpenseQueryService;
import ec.paktay.business.service.ExpenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/expenses")
@Tag(name = "Usuario · Gastos", description = "Consulta de gastos confirmados del usuario autenticado")
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
    @Operation(summary = "Registrar un gasto manual", description = "Ruta autenticada. Persiste el gasto en PostgreSQL. idempotencyKey permite que el móvil reintente sin crear duplicados.")
    @ApiResponse(responseCode = "201", description = "Gasto creado o recuperado por idempotencia")
    @ApiResponse(responseCode = "400", description = "Tarjeta, categoría, moneda, monto o recurrencia inválidos")
    public ExpenseResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateExpenseRequest request) {
        return expenseWriter.createManual(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    @Operation(summary = "Listar mis gastos", description = "Filtra gastos propios por rango inclusivo de fechas, tarjeta y categoría. Sin fechas devuelve todo el historial del usuario.")
    @ApiResponse(responseCode = "200", description = "Historial consultado")
    public List<ExpenseResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Fecha inicial inclusiva, formato yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha final inclusiva, formato yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID cardId,
            @RequestParam(required = false) UUID categoryId) {
        return expenses.list(UUID.fromString(jwt.getSubject()), from, to, cardId, categoryId);
    }
}
