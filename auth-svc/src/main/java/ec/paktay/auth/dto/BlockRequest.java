package ec.paktay.auth.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Nuevo bloqueo. type EMAIL (se guarda normalizado), DOMAIN (no se permiten dominios
 * públicos como gmail.com) o IP. scopes: TICKETS, REGISTRATION, ACCOUNT (ACCOUNT solo
 * con EMAIL: bloquea la cuenta existente y cierra sus sesiones).
 */
public record BlockRequest(
        @NotNull @Pattern(regexp = "EMAIL|DOMAIN|IP") String type,
        @NotBlank @Size(max = 320) String value,
        @NotEmpty List<@Pattern(regexp = "TICKETS|REGISTRATION|ACCOUNT") String> scopes,
        @NotNull @Pattern(regexp = "FAKE_EMAIL|OFFENSIVE|SPAM|OTHER") String reason,
        boolean alsoBlockIp) {
}
