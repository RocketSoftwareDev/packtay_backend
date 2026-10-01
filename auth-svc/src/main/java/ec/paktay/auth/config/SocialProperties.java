package ec.paktay.auth.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Iniciar sesión con Apple y Google (día 8c).
 *
 * - googleClientIds / appleClientIds: valores aceptados en el aud del ID token, separados por
 *   coma. Google: el Client ID de iOS (y el Web si un día se usa). Apple: el bundle id de la app.
 *   Vacío = ese proveedor apagado (responde 404).
 * - googleAlias / appleAlias: alias del proveedor de identidad en el realm de Keycloak.
 * - appleTeamId, appleKeyId, applePrivateKeyPath: la key de Sign in with Apple (.p8), para
 *   canjear el código de autorización y revocar al eliminar la cuenta. Sin ella el login funciona,
 *   pero no se puede revocar (queda un aviso en el log).
 */
@ConfigurationProperties(prefix = "paktay.social")
public record SocialProperties(
        String googleClientIds,
        String appleClientIds,
        String googleAlias,
        String appleAlias,
        String appleTeamId,
        String appleKeyId,
        String applePrivateKeyPath,
        String googleIssuerOverride,
        String googleJwksUrlOverride) {

    /*
     * googleIssuerOverride / googleJwksUrlOverride: SOLO para pruebas locales. Permiten que
     * Codex pruebe el flujo completo con un proveedor falso (un realm de Keycloak que emite
     * tokens como Google). En producción van vacías y mandan los de Google.
     */

    public List<String> googleAudiences() {
        return split(googleClientIds);
    }

    public List<String> appleAudiences() {
        return split(appleClientIds);
    }

    public boolean appleRevocationConfigured() {
        return notBlank(appleTeamId) && notBlank(appleKeyId) && notBlank(applePrivateKeyPath) && !appleAudiences().isEmpty();
    }

    private static List<String> split(String csv) {
        if (csv == null) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
