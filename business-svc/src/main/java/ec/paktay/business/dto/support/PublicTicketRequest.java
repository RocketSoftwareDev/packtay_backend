package ec.paktay.business.dto.support;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Formulario público de soporte. website es un campo trampa: la web lo deja oculto y
 * vacío; si llega con valor, lo llenó un bot y el ticket se descarta en silencio.
 */
public record PublicTicketRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 5, max = 2000) String reason,
        @Size(max = 200) String website) {
}
