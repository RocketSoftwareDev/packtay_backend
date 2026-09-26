package ec.paktay.auth.service;

import java.util.Map;

import ec.paktay.auth.dto.RegisterRequest;
import ec.paktay.auth.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Registro de una cuenta nueva.
 *
 * - Un correo o dominio bloqueado para REGISTRATION recibe un error genérico, sin decir
 *   que está bloqueado.
 * - Crea app_users con el correo y el nombre desde el primer momento (antes solo se
 *   llenaban al abrir el perfil), así el panel y soporte los ven.
 * - Deja el alta en la bitácora del panel.
 */
@Service
public class RegistrationService {
    static final String GENERIC_REJECTION = "No fue posible registrar la cuenta";
    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final KeycloakIdentityService identities;
    private final IdentityBlockService blocks;
    private final JdbcTemplate db;
    private final AdminAuditWriter audit;

    public RegistrationService(KeycloakIdentityService identities, IdentityBlockService blocks, JdbcTemplate db,
                               AdminAuditWriter audit) {
        this.identities = identities;
        this.blocks = blocks;
        this.db = db;
        this.audit = audit;
    }

    public UserResponse register(RegisterRequest request) {
        if (blocks.isRegistrationBlocked(request.email())) {
            log.warn("register_blocked");
            throw new IllegalArgumentException(GENERIC_REJECTION);
        }
        UserResponse created = identities.register(request);
        String email = request.email().toLowerCase();
        try {
            db.update("""
                    insert into app_users (id, email, display_name) values (cast(? as uuid), ?, ?)
                    on conflict (id) do update set email = excluded.email, display_name = excluded.display_name
                    """, created.id(), email, request.displayName());
        } catch (RuntimeException ex) {
            // business-svc crea la fila en la primera petición del usuario; no se pierde la cuenta.
            log.error("register_profile_insert_failed userId={} reason={}", created.id(), ex.getMessage());
        }
        audit.userCreated(created.id(), email, Map.of("source", "app"));
        return created;
    }
}
