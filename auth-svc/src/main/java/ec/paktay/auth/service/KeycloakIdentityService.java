package ec.paktay.auth.service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ec.paktay.auth.config.KeycloakProperties;
import ec.paktay.auth.dto.LoginRequest;
import ec.paktay.auth.dto.RegisterRequest;
import ec.paktay.auth.dto.TokenResponse;
import ec.paktay.auth.dto.UserResponse;
import ec.paktay.auth.exception.AdminSessionException;
import ec.paktay.auth.exception.CodedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class KeycloakIdentityService {
    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityService.class);
    private static final Pattern KEYCLOAK_ERROR = Pattern.compile("\\\"error\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,64})\\\"");
    private static final Pattern KEYCLOAK_ERROR_DESCRIPTION = Pattern.compile("\\\"error_description\\\"\\s*:\\s*\\\"([^\\\"]{1,256})\\\"");
    private final RestClient client;
    private final KeycloakProperties properties;

    public KeycloakIdentityService(RestClient keycloakRestClient, KeycloakProperties properties) {
        this.client = keycloakRestClient;
        this.properties = properties;
    }

    public UserResponse register(RegisterRequest request) {
        String adminToken = adminToken();
        String[] names = splitDisplayName(request.displayName());
        // Keycloak 26 exige nombre y apellido en su perfil de usuario por defecto: sin
        // lastName la cuenta se crea pero el login responde "Account is not fully set up".
        Map<String, Object> payload = Map.of(
                "username", request.email().toLowerCase(),
                "email", request.email().toLowerCase(),
                "firstName", names[0],
                "lastName", names[1],
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
                log.warn("keycloak_register_rejected status={} errorCode={}", ex.getStatusCode().value(), keycloakErrorCode(ex));
                throw new IllegalArgumentException("Ya existe una cuenta con ese correo");
            }
            log.error("keycloak_register_failed status={} errorCode={}", ex.getStatusCode().value(), keycloakErrorCode(ex));
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
                String reason = keycloakErrorDescription(ex);
                log.warn("keycloak_login_rejected status={} errorCode={} reason={}", ex.getStatusCode().value(), keycloakErrorCode(ex), reason);
                // Identidad desactivada = bloqueada por un administrador: la app ofrece soporte.
                // El bloqueo temporal por intentos fallidos Keycloak lo informa como credenciales inválidas.
                if ("Account disabled".equals(reason)) throw CodedException.accountBlocked();
                // Código estable para que la app cuente los intentos fallidos (mismo mensaje de siempre).
                throw new CodedException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIALS", "Credenciales inválidas");
            }
            log.error("keycloak_login_failed status={} errorCode={}", ex.getStatusCode().value(), keycloakErrorCode(ex));
            throw new IllegalStateException("No fue posible iniciar sesión en Keycloak");
        }
    }

    /** Login del panel con el cliente confidencial paktay-admin-panel (el rol lo revisa AdminSessionService). */
    public TokenResponse adminPanelLogin(String username, String password) {
        return adminPanelToken("grant_type=password&username=" + encode(username) + "&password=" + encode(password), false);
    }

    public TokenResponse adminPanelRefresh(String refreshToken) {
        return adminPanelToken("grant_type=refresh_token&refresh_token=" + encode(refreshToken), true);
    }

    /** Cierra la sesión de Keycloak de ese refresh token. Si ya no existe, no hay nada que cerrar. */
    public void adminPanelLogout(String refreshToken) {
        try {
            client.post().uri("/realms/" + properties.realm() + "/protocol/openid-connect/logout")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(adminPanelClient() + "&refresh_token=" + encode(refreshToken))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            log.warn("keycloak_admin_panel_logout_failed status={} errorCode={}", ex.getStatusCode().value(), keycloakErrorCode(ex));
        }
    }

    private TokenResponse adminPanelToken(String grant, boolean refresh) {
        try {
            return client.post().uri(tokenPath())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(grant + "&" + adminPanelClient())
                    .retrieve().body(TokenResponse.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                String reason = keycloakErrorDescription(ex);
                log.warn("keycloak_admin_panel_rejected refresh={} status={} errorCode={} reason={}", refresh,
                        ex.getStatusCode().value(), keycloakErrorCode(ex), reason);
                // Keycloak solo lo dice con la contraseña correcta: contraseña temporal pendiente de cambio.
                if (!refresh && "Account is not fully set up".equals(reason)) throw AdminSessionException.passwordChangeRequired();
                throw refresh ? AdminSessionException.sessionExpired() : AdminSessionException.invalidCredentials();
            }
            log.error("keycloak_admin_panel_failed status={} errorCode={}", ex.getStatusCode().value(), keycloakErrorCode(ex));
            throw new IllegalStateException("No fue posible iniciar sesión");
        }
    }

    private String adminPanelClient() {
        return "client_id=" + encode(properties.adminPanelClientId()) + "&client_secret=" + encode(properties.adminPanelClientSecret());
    }

    public void verifyCredentials(String username, String password) {
        try {
            login(new LoginRequest(username, password));
        } catch (CodedException ex) {
            throw new IllegalArgumentException(ex.getMessage());
        }
    }

    public void replacePassword(String userId, String password, boolean temporary) {
        client.put().uri(adminPath("users/" + userId + "/reset-password"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", password, "temporary", temporary))
                .retrieve().toBodilessEntity();
    }

    /**
     * El correo va como variable de plantilla y no pegado en la consulta: así se
     * codifica entero y un "+" llega como %2B. Pegado, Keycloak lo leía como un
     * espacio y "ana+pruebas@gmail.com" no encontraba a nadie (el PIN no salía).
     */
    public Map<?, ?> findByEmail(String email) {
        List<?> users = client.get().uri(builder -> builder.path(adminPath("users"))
                .queryParam("email", "{email}").queryParam("exact", true).build(email))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()).retrieve().body(List.class);
        if (users == null || users.size() != 1) return null;
        Map<?, ?> user = (Map<?, ?>) users.get(0);
        return email.equalsIgnoreCase(String.valueOf(user.get("email"))) ? user : null;
    }

    public Map<?, ?> findById(String userId) {
        try {
            return client.get().uri(adminPath("users/" + userId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()).retrieve().body(Map.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return null;
            throw ex;
        }
    }

    public void deleteUser(String userId) {
        client.delete().uri(adminPath("users/" + userId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .retrieve().toBodilessEntity();
    }

    /** Activa o desactiva la identidad. Desactivada no puede iniciar sesión ni renovar tokens. */
    public void setEnabled(String userId, boolean enabled) {
        client.put().uri(adminPath("users/" + userId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("enabled", enabled))
                .retrieve().toBodilessEntity();
    }

    /** Cierra todas las sesiones del usuario (sus refresh tokens dejan de servir). */
    public void logout(String userId) {
        client.post().uri(adminPath("users/" + userId + "/logout"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .retrieve().toBodilessEntity();
    }

    /**
     * Quita el bloqueo temporal por intentos fallidos (fuerza bruta del realm). Se llama después de
     * que la persona demostró ser dueña del correo (PIN) o recibió una contraseña nueva del admin.
     * Si falla no rompe nada: el bloqueo vence solo en minutos.
     */
    public void clearBruteForce(String userId) {
        try {
            client.delete().uri(adminPath("attack-detection/brute-force/users/" + userId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException ex) {
            log.warn("keycloak_brute_force_clear_failed subject={} reason={}", userId, ex.getMessage());
        }
    }

    /** Si la cuenta está pausada por intentos fallidos (fuerza bruta del realm). */
    public boolean isTemporarilyLocked(String userId) {
        try {
            Map<?, ?> status = client.get().uri(adminPath("attack-detection/brute-force/users/" + userId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                    .retrieve().body(Map.class);
            return status != null && Boolean.TRUE.equals(status.get("disabled"));
        } catch (RuntimeException ex) {
            log.warn("keycloak_brute_force_status_failed subject={} reason={}", userId, ex.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- día 8c · Apple y Google

    /** Id del usuario vinculado a esa cuenta de Apple o Google, o null. */
    public String findByFederatedIdentity(String alias, String providerUserId) {
        List<?> users = client.get().uri(builder -> builder.path(adminPath("users"))
                        .queryParam("idpAlias", "{alias}").queryParam("idpUserId", "{sub}").build(alias, providerUserId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()).retrieve().body(List.class);
        if (users == null || users.size() != 1) return null;
        Object id = ((Map<?, ?>) users.get(0)).get("id");
        return id == null ? null : String.valueOf(id);
    }

    /**
     * Crea la identidad de quien entra con Apple o Google: correo verificado por el proveedor,
     * sin contraseña, con el rol USER. Devuelve null si el correo ya existe (otra petición la
     * creó a la vez): quien llama la busca por correo y la vincula.
     */
    public String createSocialUser(String email, String displayName) {
        String adminToken = adminToken();
        // Keycloak exige nombre y apellido; sin nombre del proveedor se usa uno genérico que la
        // app no muestra (el nombre visible es app_users.display_name).
        String[] names = splitDisplayName(displayName == null || displayName.isBlank() ? "PAKTAY" : displayName);
        Map<String, Object> payload = Map.of(
                "username", email,
                "email", email,
                "firstName", names[0],
                "lastName", names[1],
                "enabled", true,
                "emailVerified", true);
        try {
            URI location = client.post().uri(adminPath("users"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve()
                    .toBodilessEntity().getHeaders().getLocation();
            if (location == null) throw new IllegalStateException("Keycloak no devolvió el identificador del usuario");
            String id = location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
            assignUserRole(adminToken, id);
            return id;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) return null;
            log.error("keycloak_social_create_failed status={} errorCode={}", ex.getStatusCode().value(), keycloakErrorCode(ex));
            throw new IllegalStateException("No fue posible crear la cuenta");
        }
    }

    /** Vincula la cuenta de Keycloak con la de Apple o Google (el token exchange no lo hace solo). */
    public void linkFederatedIdentity(String userId, String alias, String providerUserId, String userName) {
        try {
            client.post().uri(adminPath("users/" + userId + "/federated-identity/" + alias))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("identityProvider", alias, "userId", providerUserId, "userName", userName))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            // 409 = ya estaba vinculada (dos entradas a la vez): no es un error.
            if (ex.getStatusCode().value() == 409) return;
            log.error("keycloak_social_link_failed subject={} status={} errorCode={}", userId, ex.getStatusCode().value(), keycloakErrorCode(ex));
            throw new IllegalStateException("No fue posible vincular la cuenta");
        }
    }

    /**
     * Cambia el ID token de Apple o Google por una sesión de PAKTAY (token exchange de externo a
     * interno, función en vista previa de Keycloak 26.2: token-exchange:v1 y
     * admin-fine-grained-authz:v1). Sin audience: el token sale para paktay-auth-service, y por eso
     * la app renueva con {@link #serviceRefresh}.
     */
    public TokenResponse exchangeExternalToken(String alias, String subjectToken) {
        try {
            return client.post().uri(tokenPath())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=" + encode("urn:ietf:params:oauth:grant-type:token-exchange")
                            + "&" + serviceClient()
                            + "&subject_token=" + encode(subjectToken)
                            + "&subject_issuer=" + encode(alias)
                            + "&subject_token_type=" + encode("urn:ietf:params:oauth:token-type:jwt")
                            + "&requested_token_type=" + encode("urn:ietf:params:oauth:token-type:refresh_token"))
                    .retrieve().body(TokenResponse.class);
        } catch (RestClientResponseException ex) {
            String reason = keycloakErrorDescription(ex);
            log.warn("keycloak_token_exchange_rejected alias={} status={} errorCode={} reason={}", alias,
                    ex.getStatusCode().value(), keycloakErrorCode(ex), reason);
            if (reason.toLowerCase().contains("disabled")) throw CodedException.accountBlocked();
            if (ex.getStatusCode().is4xxClientError()) {
                throw new CodedException(HttpStatus.BAD_REQUEST, "SOCIAL_TOKEN_INVALID", "No pudimos verificar tu cuenta. Inténtalo otra vez.");
            }
            throw new IllegalStateException("No fue posible iniciar sesión");
        }
    }

    /** Renueva una sesión emitida por el token exchange (cliente paktay-auth-service). */
    public TokenResponse serviceRefresh(String refreshToken) {
        try {
            return client.post().uri(tokenPath())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=refresh_token&" + serviceClient() + "&refresh_token=" + encode(refreshToken))
                    .retrieve().body(TokenResponse.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                String reason = keycloakErrorDescription(ex);
                log.warn("keycloak_service_refresh_rejected status={} errorCode={} reason={}", ex.getStatusCode().value(), keycloakErrorCode(ex), reason);
                if ("Account disabled".equals(reason)) throw CodedException.accountBlocked();
                throw new CodedException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Tu sesión venció. Vuelve a entrar.");
            }
            log.error("keycloak_service_refresh_failed status={} errorCode={}", ex.getStatusCode().value(), keycloakErrorCode(ex));
            throw new IllegalStateException("No fue posible renovar la sesión");
        }
    }

    /** Si la cuenta tiene contraseña en Keycloak (quien solo entra con Apple o Google no la tiene). */
    public boolean hasPassword(String userId) {
        List<?> credentials = client.get().uri(adminPath("users/" + userId + "/credentials"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()).retrieve().body(List.class);
        if (credentials == null) return false;
        return credentials.stream().anyMatch(c -> c instanceof Map<?, ?> map && "password".equals(map.get("type")));
    }

    /** Alias de los proveedores vinculados a la cuenta (p. ej. ["apple"]). */
    public List<String> federatedProviders(String userId) {
        List<?> links = client.get().uri(adminPath("users/" + userId + "/federated-identity"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()).retrieve().body(List.class);
        if (links == null) return List.of();
        return links.stream().filter(l -> l instanceof Map<?, ?>)
                .map(l -> String.valueOf(((Map<?, ?>) l).get("identityProvider"))).toList();
    }

    private String serviceClient() {
        return "client_id=" + encode(properties.serviceClientId()) + "&client_secret=" + encode(properties.serviceClientSecret());
    }

    public void grantRealmRole(String userId, String roleName) {
        String token = adminToken();
        client.post().uri(adminPath("users/" + userId + "/role-mappings/realm"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(List.of(realmRole(token, roleName)))
                .retrieve().toBodilessEntity();
    }

    public void revokeRealmRole(String userId, String roleName) {
        String token = adminToken();
        client.method(org.springframework.http.HttpMethod.DELETE).uri(adminPath("users/" + userId + "/role-mappings/realm"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(List.of(realmRole(token, roleName)))
                .retrieve().toBodilessEntity();
    }

    /** Usuarios con el rol de realm (asignación directa), hasta 500. */
    public List<Map<?, ?>> usersWithRealmRole(String roleName) {
        List<?> users = client.get().uri(builder -> builder.path(adminPath("roles/" + roleName + "/users"))
                        .queryParam("first", 0).queryParam("max", 500).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()).retrieve().body(List.class);
        if (users == null) return List.of();
        List<Map<?, ?>> result = new java.util.ArrayList<>();
        for (Object user : users) {
            if (user instanceof Map<?, ?> map) result.add(map);
        }
        return result;
    }

    private Map<?, ?> realmRole(String adminToken, String roleName) {
        Map<?, ?> role = client.get().uri(adminPath("roles/" + roleName))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve().body(Map.class);
        if (role == null) throw new IllegalStateException("No existe el rol " + roleName + " en Keycloak");
        return role;
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
            log.error("keycloak_service_token_failed status={} errorCode={}", ex.getStatusCode().value(), keycloakErrorCode(ex));
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

    /**
     * Parte el nombre visible en nombre y apellido para Keycloak. La primera palabra es
     * el nombre y el resto el apellido; con una sola palabra se repite, porque el perfil
     * de usuario de Keycloak no admite el apellido vacío.
     */
    static String[] splitDisplayName(String displayName) {
        String clean = displayName == null ? "" : displayName.trim().replaceAll("\\s+", " ");
        int space = clean.indexOf(' ');
        if (space < 0) return new String[] {clean, clean};
        return new String[] {clean.substring(0, space), clean.substring(space + 1)};
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String keycloakErrorCode(RestClientResponseException exception) {
        Matcher matcher = KEYCLOAK_ERROR.matcher(exception.getResponseBodyAsString());
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private String keycloakErrorDescription(RestClientResponseException exception) {
        Matcher matcher = KEYCLOAK_ERROR_DESCRIPTION.matcher(exception.getResponseBodyAsString());
        return matcher.find() ? matcher.group(1) : "unknown";
    }
}
