package ec.paktay.business.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileContextRequest(
        @Schema(description = "Zona horaria IANA, por ejemplo America/Guayaquil. No se aceptan desplazamientos fijos como +05:00.",
                example = "America/Guayaquil")
        @NotBlank @Size(max = 64) String timezone,
        @Schema(description = "País ISO 3166-1 alfa-2 en mayúsculas", example = "EC")
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String countryCode) {
}
