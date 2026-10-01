package ec.paktay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Entrar con Apple o Google.
 *
 * - idToken: el ID token que la app recibió del proveedor.
 * - nonce: Apple, obligatorio: el nonce sin cifrar que la app generó (Apple guarda su SHA-256).
 * - authorizationCode: Apple, para poder revocar el acceso al eliminar la cuenta.
 * - givenName / familyName: Apple solo los da la primera vez, fuera del token.
 */
public record SocialLoginRequest(
        @NotBlank @Size(max = 8192) String idToken,
        @Size(max = 256) String nonce,
        @Size(max = 2048) String authorizationCode,
        @Size(max = 80) String givenName,
        @Size(max = 80) String familyName) {
}
