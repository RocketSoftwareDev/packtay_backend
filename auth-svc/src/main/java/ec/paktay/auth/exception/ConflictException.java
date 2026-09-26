package ec.paktay.auth.exception;

/** La operación choca con el estado actual (por ejemplo, quitar el último ADMIN): 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
