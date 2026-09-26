package ec.paktay.auth.service;

import java.util.Collection;
import java.util.Map;

import ec.paktay.auth.config.KeycloakProperties;
import ec.paktay.auth.dto.TokenResponse;
import ec.paktay.auth.exception.AdminSessionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

/**
 * Login propio del panel: la web nunca habla con Keycloak ni sabe que existe.
 *
 * El servidor de la web (BFF) manda correo y contraseña; aquí se piden los tokens a Keycloak con el
 * cliente confidencial paktay-admin-panel y se exige el rol ADMIN. Sin el rol se cierra la sesión
 * recién creada y se responde lo mismo que con una contraseña mala: nadie averigua qué correos son
 * administradores. La renovación vuelve a revisar el rol, así que quitarlo corta la sesión en la
 * siguiente renovación (máximo la vida del access token, 15 minutos).
 */
@Service
public class AdminSessionService {
    static final String ADMIN_ROLE = "ADMIN";
    private static final Logger log = LoggerFactory.getLogger(AdminSessionService.class);

    private final KeycloakIdentityService identities;
    private final JwtDecoder decoder;
    private final AdminAuditWriter audit;
    private final KeycloakProperties properties;
    private final TemporaryPasswordService temporaryPasswords;

    public AdminSessionService(KeycloakIdentityService identities, JwtDecoder decoder, AdminAuditWriter audit,
                               KeycloakProperties properties, TemporaryPasswordService temporaryPasswords) {
        this.identities = identities;
        this.decoder = decoder;
        this.audit = audit;
        this.properties = properties;
        this.temporaryPasswords = temporaryPasswords;
    }

    public TokenResponse login(String email, String password) {
        TokenResponse tokens = identities.adminPanelLogin(email.trim().toLowerCase(), password);
        Jwt jwt = requireAdmin(tokens, false);
        // Con una contraseña temporal pendiente se cambia primero en la app (el panel no tiene ese paso).
        if (temporaryPasswords.status(jwt.getSubject()) != TemporaryPasswordService.Status.NONE) {
            identities.adminPanelLogout(tokens.refreshToken());
            throw AdminSessionException.passwordChangeRequired();
        }
        AdminActor actor = AdminActor.from(jwt);
        audit.adminAction(actor, "Inicio de sesión en el panel", jwt.getSubject(), actor.label(), null, null);
        log.info("admin_panel_login subject={}", jwt.getSubject());
        return tokens;
    }

    public TokenResponse refresh(String refreshToken) {
        TokenResponse tokens = identities.adminPanelRefresh(refreshToken);
        requireAdmin(tokens, true);
        return tokens;
    }

    public void logout(String refreshToken) {
        identities.adminPanelLogout(refreshToken);
    }

    private Jwt requireAdmin(TokenResponse tokens, boolean refresh) {
        Jwt jwt;
        try {
            jwt = decoder.decode(tokens.accessToken());
        } catch (JwtException ex) {
            log.error("admin_panel_token_invalid reason={}", ex.getMessage());
            throw new IllegalStateException("No fue posible iniciar sesión");
        }
        if (hasAdminRole(jwt) && properties.adminPanelClientId().equals(jwt.getClaimAsString("azp"))) return jwt;
        log.warn("admin_panel_denied subject={} refresh={}", jwt.getSubject(), refresh);
        identities.adminPanelLogout(tokens.refreshToken());
        throw refresh ? AdminSessionException.sessionExpired() : AdminSessionException.invalidCredentials();
    }

    static boolean hasAdminRole(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return false;
        return realmAccess.get("roles") instanceof Collection<?> roles && roles.contains(ADMIN_ROLE);
    }
}
