package ec.paktay.business.exception;

/** Límite de envíos superado (formulario público de soporte): responde 429. */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
