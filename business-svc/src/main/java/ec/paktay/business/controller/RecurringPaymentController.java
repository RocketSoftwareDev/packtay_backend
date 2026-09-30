package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.ConfirmOccurrenceRequest;
import ec.paktay.business.dto.ExpenseResponse;
import ec.paktay.business.dto.RecurringListResponse;
import ec.paktay.business.dto.RecurringOccurrenceResponse;
import ec.paktay.business.dto.RecurringPaymentRequest;
import ec.paktay.business.dto.RecurringPaymentResponse;
import ec.paktay.business.service.RecurringPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/recurring-payments")
@Tag(name = "Usuario · Pagos recurrentes", description = "Gastos que se repiten (mensual o anual) y sus cobros pendientes en Por revisar")
@SecurityRequirement(name = "bearerAuth")
public class RecurringPaymentController {
    private final RecurringPaymentService recurring;

    public RecurringPaymentController(RecurringPaymentService recurring) {
        this.recurring = recurring;
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping
    @Operation(summary = "Listar mis pagos recurrentes", description = "Ruta autenticada. Activos primero por próximo cobro, luego pausados; los cancelados no se listan. "
            + "monthlyTotal suma lo que valen al mes los activos (un anual cuenta su doceava parte). Antes de responder deja pendientes los cobros que ya tocan.")
    @ApiResponse(responseCode = "200", description = "Lista y total mensual")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public RecurringListResponse list(@AuthenticationPrincipal Jwt jwt) {
        return recurring.list(user(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear un pago recurrente", description = "Ruta autenticada. Tarjeta activa y categoría activa del usuario. MONTHLY o YEARLY (con monthOfYear); "
            + "día FIRST, DAY (dayOfMonth; si el mes no lo tiene, el último) o LAST. endMonth YYYY-MM opcional e inclusive. "
            + "startsTomorrow = true desde «Agregar gasto · Se repite» (el gasto de hoy ya se guardó). Si el primer cobro es hoy, queda pendiente de inmediato.")
    @ApiResponse(responseCode = "201", description = "Pago recurrente creado")
    @ApiResponse(responseCode = "400", description = "Tarjeta o categoría inválida, día o mes faltante, o fecha de fin pasada")
    @ApiResponse(responseCode = "409", description = "Plan Gratis con 2 recurrentes activos")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public RecurringPaymentResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RecurringPaymentRequest request) {
        return recurring.create(user(jwt), request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Editar un pago recurrente", description = "Ruta autenticada. Vale desde el próximo cobro: un cobro ya pendiente conserva su monto. startsTomorrow se ignora.")
    @ApiResponse(responseCode = "200", description = "Pago recurrente actualizado")
    @ApiResponse(responseCode = "400", description = "Datos inválidos")
    @ApiResponse(responseCode = "404", description = "No existe o no es del usuario")
    @ApiResponse(responseCode = "409", description = "Está cancelado")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public RecurringPaymentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @Valid @RequestBody RecurringPaymentRequest request) {
        return recurring.update(user(jwt), id, request);
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pausar un pago recurrente", description = "Ruta autenticada. Deja de generar cobros y no cuenta para el límite del plan Gratis.")
    @ApiResponse(responseCode = "200", description = "Pausado")
    @ApiResponse(responseCode = "404", description = "No existe o no es del usuario")
    @ApiResponse(responseCode = "409", description = "No está activo")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public RecurringPaymentResponse pause(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return recurring.pause(user(jwt), id);
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Reanudar un pago recurrente", description = "Ruta autenticada. No recupera los meses pausados: el próximo cobro es desde hoy.")
    @ApiResponse(responseCode = "200", description = "Reanudado")
    @ApiResponse(responseCode = "404", description = "No existe o no es del usuario")
    @ApiResponse(responseCode = "409", description = "No está pausado, o plan Gratis con 2 recurrentes activos")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public RecurringPaymentResponse resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return recurring.resume(user(jwt), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancelar un pago recurrente", description = "Ruta autenticada. Termina la serie y cancela sus cobros pendientes. Los gastos ya registrados no cambian.")
    @ApiResponse(responseCode = "204", description = "Cancelado")
    @ApiResponse(responseCode = "404", description = "No existe o no es del usuario")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        recurring.cancel(user(jwt), id);
    }

    @GetMapping("/occurrences/pending")
    @Operation(summary = "Cobros pendientes de confirmar", description = "Ruta autenticada. Los cobros que ya tocaron y siguen en Por revisar, del más antiguo al más reciente.")
    @ApiResponse(responseCode = "200", description = "Cobros pendientes")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public List<RecurringOccurrenceResponse> pending(@AuthenticationPrincipal Jwt jwt) {
        return recurring.pending(user(jwt));
    }

    @PostMapping("/occurrences/{occurrenceId}/confirm")
    @Operation(summary = "Confirmar un cobro", description = "Ruta autenticada. Crea el gasto (MANUAL, enlazado al recurrente, con la fecha del cobro). "
            + "amount opcional para «Cambiar monto»; updateExpected = true lo usa también en los próximos cobros. Idempotente.")
    @ApiResponse(responseCode = "200", description = "Gasto creado")
    @ApiResponse(responseCode = "400", description = "La tarjeta del recurrente ya no está activa, o monto inválido")
    @ApiResponse(responseCode = "404", description = "El cobro no existe, no es del usuario o ya se resolvió")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public ExpenseResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID occurrenceId,
                                   @Valid @RequestBody(required = false) ConfirmOccurrenceRequest request) {
        return recurring.confirm(user(jwt), occurrenceId, request);
    }

    @PostMapping("/occurrences/{occurrenceId}/skip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Este mes no se cobró", description = "Ruta autenticada. Descarta sólo este cobro; la serie sigue.")
    @ApiResponse(responseCode = "204", description = "Descartado")
    @ApiResponse(responseCode = "404", description = "El cobro no existe, no es del usuario o ya se resolvió")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public void skip(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID occurrenceId) {
        recurring.skip(user(jwt), occurrenceId);
    }

    @PostMapping("/occurrences/{occurrenceId}/cancel-series")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancelar el pago recurrente desde un cobro", description = "Ruta autenticada. Este cobro no se guarda y la serie termina.")
    @ApiResponse(responseCode = "204", description = "Serie cancelada")
    @ApiResponse(responseCode = "404", description = "El cobro no existe, no es del usuario o ya se resolvió")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public void cancelSeries(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID occurrenceId) {
        recurring.cancelSeries(user(jwt), occurrenceId);
    }
}
