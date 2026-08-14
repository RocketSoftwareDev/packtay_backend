package ec.paktay.business.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateAutomaticRequest(
        @NotNull(message = "isAutomatic es obligatorio") Boolean isAutomatic) {
}
