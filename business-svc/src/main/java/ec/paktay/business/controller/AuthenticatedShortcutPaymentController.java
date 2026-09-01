package ec.paktay.business.controller;

import ec.paktay.business.dto.ShortcutPaymentRequest;
import ec.paktay.business.dto.ShortcutTransactionResponse;
import ec.paktay.business.service.AuthenticatedShortcutPaymentService;
import ec.paktay.business.service.ShortcutCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shortcut/payments")
@Tag(name = "Apple Shortcut · Pagos", description = "Recepción de pagos de Wallet usando el código personal generado por PAKTAY")
@SecurityRequirement(name = "shortcutBearer")
public class AuthenticatedShortcutPaymentController {
    private final ShortcutCredentialService credentials;
    private final AuthenticatedShortcutPaymentService payments;

    public AuthenticatedShortcutPaymentController(ShortcutCredentialService credentials,
                                                  AuthenticatedShortcutPaymentService payments) {
        this.credentials = credentials;
        this.payments = payments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar un pago desde Wallet", description = "El bearer es el código personal del Shortcut, no un JWT. Vincula el pago con su usuario y sugiere tarjeta comparando exclusivamente cardName; no usa últimos cuatro dígitos.")
    @ApiResponse(responseCode = "201", description = "Movimiento pendiente creado o reintento reconocido")
    @ApiResponse(responseCode = "400", description = "Monto, comercio, tarjeta, fecha o eventId inválidos")
    @ApiResponse(responseCode = "401", description = "Código de conexión ausente, inválido o revocado")
    public ShortcutTransactionResponse ingest(
            @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody ShortcutPaymentRequest request) {
        return payments.ingest(credentials.authenticate(authorization), request);
    }
}
