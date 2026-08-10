package ec.paktay.business.controller;

import java.util.List;
import java.util.UUID;

import ec.paktay.business.dto.CardResponse;
import ec.paktay.business.dto.CreateCardRequest;
import ec.paktay.business.service.CardService;
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
@RequestMapping("/api/v1/cards")
public class CardController {
    private final CardService cards;

    public CardController(CardService cards) { this.cards = cards; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCardRequest request) {
        return cards.register(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("name"), jwt.getClaimAsString("email"), request);
    }

    @GetMapping
    public List<CardResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return cards.list(UUID.fromString(jwt.getSubject()));
    }
}
