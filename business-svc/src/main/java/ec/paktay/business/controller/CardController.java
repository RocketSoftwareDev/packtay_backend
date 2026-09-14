package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.CardResponse;
import ec.paktay.business.dto.CreateCardRequest;
import ec.paktay.business.dto.UpdateCardRequest;
import ec.paktay.business.service.CardService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/cards")
@Tag(name = "Usuario · Tarjetas", description = "Tarjetas y presupuesto inicial del usuario autenticado")
@SecurityRequirement(name = "bearerAuth")
public class CardController {
    private final CardService cards;

    public CardController(CardService cards) { this.cards = cards; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar una tarjeta", description = "Ruta autenticada. Conserva el nombre identificador, alias visual opcional, últimos cuatro opcionales y colores. CREDIT exige creditBrand permitido; DEBIT prohíbe marca. El nombre no puede repetirse entre las tarjetas ACTIVAS del mismo banco del usuario (sí puede repetirse en bancos distintos).")
    @ApiResponse(responseCode = "201", description = "Tarjeta creada")
    @ApiResponse(responseCode = "400", description = "Banco, moneda o tarjeta inválidos, o nombre ya usado en ese banco")
    public CardResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCardRequest request) {
        return cards.register(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    @Operation(summary = "Listar mis tarjetas", description = "Devuelve todas las tarjetas del usuario autenticado (ACTIVE primero, luego INACTIVE) y su presupuesto del mes actual. El campo status permite al móvil ocultar las desactivadas de los selectores.")
    @ApiResponse(responseCode = "200", description = "Tarjetas con tipo, marca de crédito, últimos cuatro, colores y estado persistidos")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public List<CardResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return cards.list(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/{cardId}")
    @Operation(summary = "Editar alias y colores de una tarjeta", description = "Ruta autenticada. Actualiza el alias visual opcional y los colores. El nombre identificador, banco, tipo, franquicia y últimos cuatro permanecen inmutables.")
    @ApiResponse(responseCode = "200", description = "Tarjeta actualizada")
    @ApiResponse(responseCode = "400", description = "Alias o colores inválidos")
    public CardResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId,
                               @Valid @RequestBody UpdateCardRequest request) {
        return cards.update(UUID.fromString(jwt.getSubject()), cardId, request);
    }

    @PatchMapping("/{cardId}/deactivate")
    @Operation(summary = "Desactivar una tarjeta", description = "Ruta autenticada. Pasa la tarjeta a INACTIVE: conserva su historial, deja de aceptar gastos nuevos, desaparece de los selectores del móvil y libera su nombre dentro del banco. Sólo se permite si la tarjeta no registró consumos en los últimos 3 meses.")
    @ApiResponse(responseCode = "200", description = "Tarjeta desactivada")
    @ApiResponse(responseCode = "400", description = "La tarjeta no existe, ya estaba desactivada o tiene consumos en los últimos 3 meses")
    public CardResponse deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId) {
        return cards.deactivate(UUID.fromString(jwt.getSubject()), cardId);
    }

    @PatchMapping("/{cardId}/activate")
    @Operation(summary = "Reactivar una tarjeta desactivada", description = "Ruta autenticada. Devuelve la tarjeta a ACTIVE. Falla si ya existe otra tarjeta activa con el mismo nombre en el mismo banco.")
    @ApiResponse(responseCode = "200", description = "Tarjeta activa")
    @ApiResponse(responseCode = "400", description = "La tarjeta no existe, ya estaba activa o su nombre ya lo usa otra tarjeta activa del banco")
    public CardResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId) {
        return cards.activate(UUID.fromString(jwt.getSubject()), cardId);
    }

    @DeleteMapping("/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Eliminar una tarjeta sin consumos", description = "Ruta autenticada. Borra definitivamente la tarjeta junto con sus presupuestos e ingresos asociados. Sólo se permite si la tarjeta no tiene ningún gasto en todo el historial; con gastos, la alternativa es desactivarla.")
    @ApiResponse(responseCode = "204", description = "Tarjeta eliminada")
    @ApiResponse(responseCode = "400", description = "La tarjeta no existe o tiene consumos registrados")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId) {
        cards.delete(UUID.fromString(jwt.getSubject()), cardId);
    }
}
