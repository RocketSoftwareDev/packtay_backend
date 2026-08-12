package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.CardResponse;
import ec.paktay.business.dto.CreateCardRequest;
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
    @Operation(summary = "Registrar una tarjeta", description = "Crea una tarjeta del usuario y, opcionalmente, su presupuesto de tarjeta para el mes actual.")
    @ApiResponse(responseCode = "201", description = "Tarjeta creada")
    @ApiResponse(responseCode = "400", description = "Banco, moneda o tarjeta inválidos")
    public CardResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCardRequest request) {
        return cards.register(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    @Operation(summary = "Listar mis tarjetas", description = "Devuelve únicamente las tarjetas del usuario autenticado y su presupuesto del mes actual.")
    public List<CardResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return cards.list(UUID.fromString(jwt.getSubject()));
    }
}
