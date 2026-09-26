package ec.paktay.business.dto.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** REPLY se envía por correo y queda en la conversación; NOTE solo la ve el equipo. */
public record TicketReplyRequest(
        @NotBlank @Size(max = 4000) String body,
        @NotNull @Pattern(regexp = "REPLY|NOTE") String kind,
        boolean resolve) {
}
