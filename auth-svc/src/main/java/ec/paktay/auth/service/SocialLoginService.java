package ec.paktay.auth.service;

import java.util.Map;

import ec.paktay.auth.config.SocialProperties;
import ec.paktay.auth.dto.SocialLoginRequest;
import ec.paktay.auth.dto.TokenResponse;
import ec.paktay.auth.exception.CodedException;
import ec.paktay.auth.service.SocialTokenVerifier.SocialIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Entrar con Apple o Google (día 8c). Mismo camino para entrar y para registrarse:
 *
 * 1. Valida el ID token del proveedor ({@link SocialTokenVerifier}).
 * 2. Deja listo al usuario en Keycloak **antes** del token exchange, porque el exchange ni
 *    vincula por correo ni asigna el rol USER (probado en kc-spike, corridas 1954 y 2005):
 *    - ya vinculado a esa cuenta de Apple/Google → se usa;
 *    - si no, el mismo correo ya tiene cuenta → se vincula (conserva su contraseña);
 *    - si no, se crea sin contraseña, con USER, su fila en app_users y el alta en la bitácora.
 * 3. Token exchange: la sesión de PAKTAY (se renueva por /api/v1/auth/social/refresh).
 * 4. Apple: guarda su refresh token para revocarlo al eliminar la cuenta.
 *
 * Un correo bloqueado para registro no puede crear cuenta por aquí (mismo error genérico que el
 * registro); una cuenta bloqueada por un administrador recibe ACCOUNT_BLOCKED.
 */
@Service
public class SocialLoginService {
    private static final Logger log = LoggerFactory.getLogger(SocialLoginService.class);

    private final SocialTokenVerifier verifier;
    private final KeycloakIdentityService identities;
    private final IdentityBlockService blocks;
    private final AppleTokenService apple;
    private final SocialProperties properties;
    private final JdbcTemplate db;
    private final AdminAuditWriter audit;
    private final LegalAcceptanceService legal;

    public SocialLoginService(SocialTokenVerifier verifier, KeycloakIdentityService identities, IdentityBlockService blocks,
                              AppleTokenService apple, SocialProperties properties, JdbcTemplate db, AdminAuditWriter audit,
                              LegalAcceptanceService legal) {
        this.legal = legal;
        this.verifier = verifier;
        this.identities = identities;
        this.blocks = blocks;
        this.apple = apple;
        this.properties = properties;
        this.db = db;
        this.audit = audit;
    }

    public TokenResponse login(SocialProvider provider, SocialLoginRequest request) {
        SocialIdentity identity = verifier.verify(provider, request.idToken(), request.nonce());
        String alias = alias(provider);
        boolean acceptedLegal = Boolean.TRUE.equals(request.acceptedLegal());
        String userId = prepareUser(identity, alias, displayName(identity, request), acceptedLegal);
        if (acceptedLegal) legal.record(userId);
        Map<?, ?> user = identities.findById(userId);
        if (user != null && Boolean.FALSE.equals(user.get("enabled"))) throw CodedException.accountBlocked();
        TokenResponse tokens = identities.exchangeExternalToken(alias, request.idToken());
        if (provider == SocialProvider.APPLE) apple.storeRefreshToken(userId, request.authorizationCode());
        log.info("social_login_ok provider={} userId={}", provider, userId);
        return tokens;
    }

    private String prepareUser(SocialIdentity identity, String alias, String displayName, boolean acceptedLegal) {
        String linked = identities.findByFederatedIdentity(alias, identity.subject());
        if (linked != null) return linked;

        Map<?, ?> existing = identities.findByEmail(identity.email());
        if (existing != null) return link(existing, identity, alias);

        // Crear cuenta exige aceptar Términos y Privacidad (la línea bajo los botones).
        if (!acceptedLegal) {
            throw new CodedException(org.springframework.http.HttpStatus.BAD_REQUEST, "LEGAL_ACCEPTANCE_REQUIRED",
                    "Para crear la cuenta hay que aceptar los Términos de uso y la Política de privacidad.");
        }
        if (blocks.isRegistrationBlocked(identity.email())) {
            log.warn("social_register_blocked provider={}", identity.provider());
            throw new IllegalArgumentException(RegistrationService.GENERIC_REJECTION);
        }
        String created = identities.createSocialUser(identity.email(), displayName);
        if (created == null) {
            // Otra entrada con el mismo correo la creó a la vez: se vincula esa.
            Map<?, ?> raced = identities.findByEmail(identity.email());
            if (raced == null) throw new IllegalStateException("No fue posible crear la cuenta");
            return link(raced, identity, alias);
        }
        identities.linkFederatedIdentity(created, alias, identity.subject(), identity.email());
        try {
            db.update("""
                    insert into app_users (id, email, display_name) values (cast(? as uuid), ?, ?)
                    on conflict (id) do update set email = excluded.email,
                        display_name = coalesce(excluded.display_name, app_users.display_name)
                    """, created, identity.email(), displayName);
        } catch (RuntimeException ex) {
            // business-svc crea la fila en la primera petición del usuario; no se pierde la cuenta.
            log.error("social_profile_insert_failed userId={} reason={}", created, ex.getMessage());
        }
        audit.userCreated(created, identity.email(), Map.of("source", identity.provider().name().toLowerCase()));
        return created;
    }

    private String link(Map<?, ?> user, SocialIdentity identity, String alias) {
        String id = String.valueOf(user.get("id"));
        identities.linkFederatedIdentity(id, alias, identity.subject(), identity.email());
        log.info("social_account_linked provider={} userId={}", identity.provider(), id);
        return id;
    }

    /**
     * Nombre visible: Google lo trae en el token; Apple solo lo da la primera vez y la app lo
     * manda aparte. Sin nombre queda null y se completa en Editar perfil (no se inventa uno a
     * partir del correo, que con Apple suele ser una dirección oculta).
     */
    static String displayName(SocialIdentity identity, SocialLoginRequest request) {
        String given = firstNonBlank(request.givenName(), identity.givenName());
        String family = firstNonBlank(request.familyName(), identity.familyName());
        String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
        return joined.isEmpty() ? null : joined;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private String alias(SocialProvider provider) {
        return provider == SocialProvider.APPLE ? properties.appleAlias() : properties.googleAlias();
    }
}
