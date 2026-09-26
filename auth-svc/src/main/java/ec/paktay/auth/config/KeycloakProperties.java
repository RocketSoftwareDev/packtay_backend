package ec.paktay.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * adminPanelClientId/Secret: cliente confidencial paktay-admin-panel. Solo auth-svc lo usa para
 * el login propio del panel (direct grant); el secreto nunca sale del servidor.
 */
@ConfigurationProperties(prefix = "paktay.keycloak")
public record KeycloakProperties(
        String internalUrl,
        String publicUrl,
        String realm,
        String mobileClientId,
        String serviceClientId,
        String serviceClientSecret,
        String adminPanelClientId,
        String adminPanelClientSecret) {
}
