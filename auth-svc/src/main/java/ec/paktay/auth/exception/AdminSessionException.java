package ec.paktay.auth.exception;

import org.springframework.http.HttpStatus;

/** Rechazo del login o de la renovación del panel, con un código estable para la web. */
public class AdminSessionException extends CodedException {

    public AdminSessionException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    /** Mismo mensaje para contraseña mala, cuenta sin ADMIN, bloqueada o con demasiados intentos. */
    public static AdminSessionException invalidCredentials() {
        return new AdminSessionException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Correo o contraseña incorrectos");
    }

    public static AdminSessionException sessionExpired() {
        return new AdminSessionException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Tu sesión expiró. Vuelve a iniciar sesión.");
    }

    public static AdminSessionException passwordChangeRequired() {
        return new AdminSessionException(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
                "Tu contraseña es temporal. Cámbiala en la app PAKTAY y vuelve a entrar.");
    }
}
