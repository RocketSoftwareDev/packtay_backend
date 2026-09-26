package ec.paktay.business.controller;

import ec.paktay.business.dto.support.PublicTicketRequest;
import ec.paktay.business.dto.support.PublicTicketResponse;
import ec.paktay.business.service.ClientIp;
import ec.paktay.business.service.SupportTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
@RequestMapping("/api/v1/public/support")
@Tag(name = "Soporte público", description = "Rutas públicas, sin token, para el formulario de soporte de la web.")
public class PublicSupportController {
    private final SupportTicketService tickets;

    public PublicSupportController(SupportTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Crear ticket de soporte",
            description = "Ruta pública; no requiere token. Recibe correo y motivo, y responde el código del ticket "
                    + "(PK-0001). El ticket queda PENDING_VERIFICATION hasta que el usuario abre el enlace que le llega por "
                    + "correo; solo entonces aparece en el panel. Límites: 5 por hora por IP y 3 por día por correo. "
                    + "El campo website debe ir vacío (trampa para bots). Un correo, dominio o IP bloqueado recibe la "
                    + "misma respuesta, sin guardar nada.")
    @ApiResponse(responseCode = "202", description = "Solicitud recibida; falta confirmar el correo")
    @ApiResponse(responseCode = "400", description = "Correo o motivo inválido")
    @ApiResponse(responseCode = "429", description = "Demasiadas solicitudes")
    public PublicTicketResponse create(@Valid @RequestBody PublicTicketRequest request, HttpServletRequest http) {
        return tickets.create(request, ClientIp.of(http), http.getHeader("User-Agent"));
    }

    @GetMapping(value = "/verify", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Confirmar el correo de un ticket",
            description = "Ruta pública; no requiere token. Es el enlace del correo de confirmación. Responde una página simple. "
                    + "Si la web del formulario prefiere su propia página, puede llamar a esta ruta y mostrar el resultado.")
    @ApiResponse(responseCode = "200", description = "Correo confirmado")
    @ApiResponse(responseCode = "410", description = "Enlace vencido o ya usado")
    public ResponseEntity<String> verify(@RequestParam(required = false) String token) {
        return tickets.verify(token)
                .map(code -> ResponseEntity.ok(page("Solicitud confirmada",
                        "Tu solicitud " + code + " llegó al equipo de PAKTAY. Te responderemos a este correo.")))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.GONE).body(page("Enlace no válido",
                        "El enlace venció o ya se usó. Si necesitas ayuda, envía una nueva solicitud.")));
    }

    private static String page(String title, String message) {
        return "<!doctype html><html lang=\"es\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + HtmlUtils.htmlEscape(title) + " · PAKTAY</title>"
                + "<body style=\"font-family:system-ui,sans-serif;background:#faf8f5;color:#26221f;display:grid;place-items:center;min-height:100vh;margin:0\">"
                + "<main style=\"max-width:420px;padding:32px;background:#fff;border:1px solid #e7e1da;border-radius:20px;text-align:center\">"
                + "<h1 style=\"font-size:20px\">" + HtmlUtils.htmlEscape(title) + "</h1>"
                + "<p style=\"color:#6b635c\">" + HtmlUtils.htmlEscape(message) + "</p></main></body></html>";
    }
}
