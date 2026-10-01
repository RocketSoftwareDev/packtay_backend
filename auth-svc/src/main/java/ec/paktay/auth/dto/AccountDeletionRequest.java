package ec.paktay.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * password: obligatoria si la cuenta tiene contraseña. Quien solo entra con Apple o Google no
 * tiene: la app lo confirma con Face ID y manda el cuerpo sin contraseña (día 8c).
 */
public record AccountDeletionRequest(@Size(max = 256) String password) {
}
