package ec.paktay.business.exception;

/** Un servicio externo (correo) no respondió: 502 y la operación no se guarda. */
public class UpstreamException extends RuntimeException {
    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
