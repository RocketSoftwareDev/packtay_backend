package ec.paktay.auth.exception;

import org.springframework.http.HttpStatus;

/** Rechazo con un código estable que la app o la web leen para decidir qué mostrar. */
public class CodedException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public CodedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Cuenta bloqueada por un administrador: la app ofrece escribir a soporte. */
    public static CodedException accountBlocked() {
        return new CodedException(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED",
                "Hemos detectado que tu cuenta está bloqueada. Contacta con soporte.");
    }

    public static CodedException temporaryPasswordExpired() {
        return new CodedException(HttpStatus.BAD_REQUEST, "TEMPORARY_PASSWORD_EXPIRED",
                "Tu contraseña temporal venció. Toca «¿La olvidaste?» para crear una nueva.");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
