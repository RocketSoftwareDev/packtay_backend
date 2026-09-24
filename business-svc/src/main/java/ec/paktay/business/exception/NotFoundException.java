package ec.paktay.business.exception;

/** El recurso no existe o no pertenece al usuario autenticado. Se responde 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
