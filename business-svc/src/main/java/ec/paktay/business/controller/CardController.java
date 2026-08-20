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
import org.springframework.web.bind.annotation.GetMapping;
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
    @Operation(summary = "Registrar una tarjeta", description = "Ruta autenticada. Valida el tipo contra la oferta del banco. CREDIT exige creditBrand permitido; DEBIT prohíbe marca. name es el nombre real de la tarjeta tal como llega en las notificaciones (ej. TITANIUM Visa) y se usa para asociar consumos entrantes; alias es un apodo opcional para mostrar. last4 es opcional: si se envía debe tener exactamente cuatro dígitos y ser único por banco entre tarjetas activas.")
    @ApiResponse(responseCode = "201", description = "Tarjeta creada")
    @ApiResponse(responseCode = "400", description = "Banco, moneda o tarjeta inválidos")
    public CardResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCardRequest request) {
        return cards.register(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    @Operation(summary = "Listar mis tarjetas", description = "Devuelve únicamente las tarjetas del usuario autenticado y su presupuesto del mes actual.")
    @ApiResponse(responseCode = "200", description = "Tarjetas con tipo, marca de crédito, nombre real, alias, últimos cuatro (puede ser nulo) y colores persistidos")
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido")
    public List<CardResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return cards.list(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/{cardId}")
    @Operation(summary = "Editar nombre, alias y colores de una tarjeta", description = "Ruta autenticada. Actualiza el nombre real (clave de asociación de consumos), el alias opcional y los colores. Enviar alias nulo o vacío lo elimina. Banco, tipo, franquicia y últimos cuatro permanecen inmutables.")
    @ApiResponse(responseCode = "200", description = "Tarjeta actualizada")
    @ApiResponse(responseCode = "400", description = "Nombre, alias o colores inválidos")
    public CardResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId,
                               @Valid @RequestBody UpdateCardRequest request) {
        return cards.update(UUID.fromString(jwt.getSubject()), cardId, request);
    }
}
