package ec.paktay.business.exception;

/** La operación choca con el estado actual del recurso (por ejemplo, editar un gasto anulado). Se responde 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
