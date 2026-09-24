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
 * Eliminación de cuenta, inmediata.
 *
 * 1. Confirma la contraseña contra Keycloak.
 * 2. Borra todos los datos del usuario en la base de negocio con purge_user (V6),
 *    en una transacción. Sólo se conserva subscription_event, el registro de
 *    compras de planes.
 * 3. Borra el usuario en Keycloak, lo que cierra todas sus sesiones.
 *
 * Los datos van primero: si Keycloak fallara después, el usuario queda sin datos
 * y la app puede reintentar (purge_user es idempotente); al revés quedarían datos
 * de alguien que ya no puede entrar a borrarlos.
 */
@Service
public class AccountDeletionService {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final KeycloakIdentityService identities;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public AccountDeletionService(KeycloakIdentityService identities, JdbcTemplate db, PlatformTransactionManager manager) {
        this.identities = identities;
        this.db = db;
        this.tx = new TransactionTemplate(manager);
    }

    public void delete(String subject, String password) {
        Map<?, ?> user = identities.findById(subject);
        if (user != null) {
            try {
                identities.verifyCredentials(String.valueOf(user.get("username")), password);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("La contraseña no es correcta");
            }
        }
        tx.executeWithoutResult(status -> db.queryForList("select public.purge_user(cast(? as uuid))", subject));
        log.info("account_data_purged subject={}", subject);
        if (user == null) return;
        try {
            identities.deleteUser(subject);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                log.error("account_identity_delete_failed subject={} status={}", subject, ex.getStatusCode().value());
                throw new IllegalStateException("Tus datos se borraron, pero no pudimos cerrar la cuenta. Vuelve a intentarlo.");
            }
        }
        log.info("account_deleted subject={}", subject);
    }
}
