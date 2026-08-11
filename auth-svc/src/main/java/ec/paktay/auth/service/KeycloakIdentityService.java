package ec.paktay.auth.service;

import java.net.URI;
import java.util.List;
import java.util.Map;

import ec.paktay.auth.config.KeycloakProperties;
import ec.paktay.auth.dto.LoginRequest;
import ec.paktay.auth.dto.RegisterRequest;
import ec.paktay.auth.dto.TokenResponse;
import ec.paktay.auth.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class KeycloakIdentityService {
    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityService.class);
    private final RestClient client;
    private final KeycloakProperties properties;

    public KeycloakIdentityService(RestClient keycloakRestClient, KeycloakProperties properties) {
        this.client = keycloakRestClient;
        this.properties = properties;
    }

    public UserResponse register(RegisterRequest request) {
        String adminToken = adminToken();
        Map<String, Object> payload = Map.of(
                "username", request.email().toLowerCase(),
                "email", request.email().toLowerCase(),
                "firstName", request.displayName(),
                "enabled", true,
                "emailVerified", false,
                "credentials", List.of(Map.of("type", "password", "value", request.password(), "temporary", false)));
        try {
            URI location = client.post().uri(adminPath("users"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve()
                    .toBodilessEntity().getHeaders().getLocation();
            if (location == null) throw new IllegalStateException("Keycloak no devolvió el identificador del usuario");
            String id = location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
            assignUserRole(adminToken, id);
            return new UserResponse(id, request.email(), request.displayName(), true);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                log.warn("keycloak_register_rejected status={}", ex.getStatusCode().value());
                throw new IllegalArgumentException("Ya existe una cuenta con ese correo");
            }
            log.error("keycloak_register_failed status={}", ex.getStatusCode().value());
            throw new IllegalStateException("No fue posible registrar la cuenta: " + ex.getStatusText());
        }
    }

    public TokenResponse login(LoginRequest request) {
        try {
            return client.post().uri(tokenPath())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=password&client_id=" + properties.mobileClientId()
                            + "&username=" + encode(request.username()) + "&password=" + encode(request.password()))
                    .retrieve().body(TokenResponse.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                log.warn("keycloak_login_rejected status={}", ex.getStatusCode().value());
                throw new IllegalArgumentException("Credenciales inválidas");
            }
            log.error("keycloak_login_failed status={}", ex.getStatusCode().value());
            throw new IllegalStateException("No fue posible iniciar sesión en Keycloak");
        }
    }

    public void verifyCredentials(String username, String password) {
        login(new LoginRequest(username, password));
    }

    public void replacePassword(String userId, String password, boolean temporary) {
        client.put().uri(adminPath("users/" + userId + "/reset-password"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", password, "temporary", temporary))
                .retrieve().toBodilessEntity();
    }

    public void deleteUser(String userId) {
        client.delete().uri(adminPath("users/" + userId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .retrieve().toBodilessEntity();
    }

    private String adminToken() {
        try {
            Map<?, ?> response = client.post().uri(tokenPath())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials&client_id=" + properties.serviceClientId()
                            + "&client_secret=" + encode(properties.serviceClientSecret()))
                    .retrieve().body(Map.class);
            if (response == null || response.get("access_token") == null) throw new IllegalStateException("Keycloak no devolvió token de servicio");
            return String.valueOf(response.get("access_token"));
        } catch (RestClientResponseException ex) {
            log.error("keycloak_service_token_failed status={}", ex.getStatusCode().value());
            throw new IllegalStateException("No fue posible autenticar el servicio con Keycloak");
        }
    }

    private void assignUserRole(String adminToken, String userId) {
        Map<?, ?> role = client.get().uri(adminPath("roles/USER"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve().body(Map.class);
        if (role == null) throw new IllegalStateException("No existe el rol USER en Keycloak");
        client.post().uri(adminPath("users/" + userId + "/role-mappings/realm"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).body(List.of(role))
                .retrieve().toBodilessEntity();
    }

    private String tokenPath() {
        return "/realms/" + properties.realm() + "/protocol/openid-connect/token";
    }

    private String adminPath(String path) {
        return "/admin/realms/" + properties.realm() + "/" + path;
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
