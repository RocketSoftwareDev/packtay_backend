package ec.paktay.business.controller;

import ec.paktay.business.dto.CreateUnregisteredPaymentRequest;
import ec.paktay.business.dto.UnregisteredPaymentResponse;
import ec.paktay.business.service.UnregisteredPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/unregistered-payments")
@Tag(name = "Público · Pagos no registrados", description = "Recepción temporal de pagos enviados por el celular antes de vincularlos con un usuario o tarjeta")
public class UnregisteredPaymentController {
    private final UnregisteredPaymentService payments;

    public UnregisteredPaymentController(UnregisteredPaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Guardar un pago no registrado",
            description = "Ruta pública, sin bearerAuth. Guarda monto, comercio y nombre de tarjeta con un ID secuencial. deviceId es opcional y queda reservado para la identificación futura del celular.")
    @ApiResponse(responseCode = "201", description = "Pago no registrado guardado")
    @ApiResponse(responseCode = "400", description = "Monto, comercio, tarjeta o deviceId inválidos")
    public UnregisteredPaymentResponse create(@Valid @RequestBody CreateUnregisteredPaymentRequest request) {
        return payments.create(request);
    }
}
