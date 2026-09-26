package ec.paktay.business.dto.support;

/** Respuesta del formulario: el código del ticket y que falta confirmar el correo. */
public record PublicTicketResponse(String ticketId, String status, String message) {
}
