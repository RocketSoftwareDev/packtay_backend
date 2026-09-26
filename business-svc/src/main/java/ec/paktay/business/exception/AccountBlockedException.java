package ec.paktay.business.exception;

/**
 * La cuenta está bloqueada por un administrador (app_users.status = INACTIVE).
 * Responde 403 con code = ACCOUNT_BLOCKED para que la app muestre "Tu cuenta fue
 * bloqueada" y cierre la sesión; no se explica el motivo.
 */
public class AccountBlockedException extends RuntimeException {
    public static final String CODE = "ACCOUNT_BLOCKED";

    public AccountBlockedException() {
        super("Tu cuenta fue bloqueada");
    }
}
