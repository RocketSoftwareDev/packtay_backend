package ec.paktay.auth.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

/**
 * Eliminación de cuenta, inmediata. La usan el propio usuario (con su contraseña) y un
 * administrador desde el panel (sin contraseña).
 *
 * 1. Borra todos los datos del usuario en la base de negocio con purge_user (V6, V9), en
 *    una transacción: incluidos sus tickets de soporte y sus eventos de la bitácora del
 *    panel. Sólo se conserva subscription_event, el registro de compras de planes.
 * 2. Borra el usuario en Keycloak, lo que cierra todas sus sesiones.
 * 3. Deja en admin_audit el cierre con un id anónimo (sin correo).
 *
 * Los datos van primero: si Keycloak fallara después, el usuario queda sin datos
 * y se puede reintentar (purge_user es idempotente); al revés quedarían datos
 * de alguien que ya no puede entrar a borrarlos.
 */
@Service
public class AccountDeletionService {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final KeycloakIdentityService identities;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AdminAuditWriter audit;

    public AccountDeletionService(KeycloakIdentityService identities, JdbcTemplate db, PlatformTransactionManager manager,
                                  AdminAuditWriter audit) {
        this.identities = identities;
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.audit = audit;
    }

    /** El propio usuario, confirmando su contraseña. */
    public void delete(String subject, String password) {
        Map<?, ?> user = identities.findById(subject);
        if (user != null) {
            try {
                identities.verifyCredentials(String.valueOf(user.get("username")), password);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("La contraseña no es correcta");
            }
        }
        purgeAndDelete(subject, user != null, null);
    }

    /** Un administrador desde el panel. */
    public void deleteByAdmin(String subject, AdminActor actor) {
        Map<?, ?> user = identities.findById(subject);
        purgeAndDelete(subject, user != null, actor);
    }

    private void purgeAndDelete(String subject, boolean identityExists, AdminActor actor) {
        tx.executeWithoutResult(status -> db.queryForList("select public.purge_user(cast(? as uuid))", subject));
        log.info("account_data_purged subject={}", subject);
        if (identityExists) {
            try {
                identities.deleteUser(subject);
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() != 404) {
                    log.error("account_identity_delete_failed subject={} status={}", subject, ex.getStatusCode().value());
                    throw new IllegalStateException("Los datos se borraron, pero no pudimos cerrar la cuenta. Vuelve a intentarlo.");
                }
            }
        }
        audit.accountDeleted(subject, actor);
        log.info("account_deleted subject={} byAdmin={}", subject, actor != null);
    }
}
