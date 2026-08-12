package ec.paktay.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paktay.keycloak")
public record KeycloakProperties(
        String internalUrl,
        String publicUrl,
        String realm,
        String mobileClientId,
        String serviceClientId,
        String serviceClientSecret) {
}
