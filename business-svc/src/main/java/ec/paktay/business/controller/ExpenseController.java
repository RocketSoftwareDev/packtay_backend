package ec.paktay.business.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.ExpenseResponse;
import ec.paktay.business.service.ExpenseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/expenses")
@Tag(name = "Usuario · Gastos", description = "Consulta de gastos confirmados del usuario autenticado")
@SecurityRequirement(name = "bearerAuth")
public class ExpenseController {
    private final ExpenseQueryService expenses;

    public ExpenseController(ExpenseQueryService expenses) { this.expenses = expenses; }

    @GetMapping
    @Operation(summary = "Listar mis gastos", description = "Filtra gastos propios por rango inclusivo de fechas, tarjeta y categoría. Sin fechas devuelve todo el historial del usuario.")
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
