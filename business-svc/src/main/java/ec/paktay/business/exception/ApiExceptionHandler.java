package ec.paktay.business.exception;

import java.sql.SQLException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** SQLSTATE de un RAISE EXCEPTION sin código propio: son las reglas de negocio de los triggers. */
    static final String PLPGSQL_RAISE_SQLSTATE = "P0001";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst().map(error -> error.getField() + ": " + error.getDefaultMessage()).orElse("Solicitud inválida");
        log.warn("request_validation_failed requestId={} field={}", MDC.get("requestId"), message);
        return body(message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> typeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("request_parameter_invalid requestId={} parameter={}", MDC.get("requestId"), ex.getName());
        return body("Parámetro inválido: " + ex.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> unreadable(HttpMessageNotReadableException ex) {
        log.warn("request_body_unreadable requestId={}", MDC.get("requestId"));
        return body("El cuerpo de la solicitud no es un JSON válido");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> business(IllegalArgumentException ex) {
        log.warn("request_rejected requestId={} reason={}", MDC.get("requestId"), ex.getMessage());
        return body(ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> notFound(NotFoundException ex) {
        log.warn("resource_not_found requestId={} reason={}", MDC.get("requestId"), ex.getMessage());
        return body(ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(ConflictException ex) {
        log.warn("request_conflict requestId={} reason={}", MDC.get("requestId"), ex.getMessage());
        return body(ex.getMessage());
    }

    /** Restricciones únicas, FK y CHECK: el dato choca con lo existente; no es una caída de la base. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> integrity(DataIntegrityViolationException ex) {
        log.warn("database_constraint_violated requestId={} reason={}", MDC.get("requestId"), ex.getMostSpecificCause().getMessage());
        return body("Conflicto con datos existentes");
    }

    /** La base no respondió al abrir la transacción (@Transactional). */
    @ExceptionHandler(CannotCreateTransactionException.class)
    ResponseEntity<Map<String, String>> transactionUnavailable(CannotCreateTransactionException ex) {
        log.error("database_unavailable requestId={} reason={}", MDC.get("requestId"), ex.getMostSpecificCause().getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body("Base de datos no disponible"));
    }

    /**
     * Resto de errores de acceso a datos:
     * - RAISE EXCEPTION de un trigger (P0001): regla de negocio, 409 con el mensaje del trigger;
     * - conexión caída, timeout o bloqueo transitorio: 503;
     * - cualquier otro (SQL mal formado, etc.): 500.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof SQLException sql && PLPGSQL_RAISE_SQLSTATE.equals(sql.getSQLState())) {
            String message = triggerMessage(sql.getMessage());
            log.warn("database_rule_rejected requestId={} reason={}", MDC.get("requestId"), message);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body(message));
        }
        if (ex instanceof DataAccessResourceFailureException || ex instanceof TransientDataAccessException
                || ex instanceof RecoverableDataAccessException) {
            log.error("database_unavailable requestId={} reason={}", MDC.get("requestId"), cause.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body("Base de datos no disponible"));
        }
        log.error("database_query_failed requestId={} reason={}", MDC.get("requestId"), cause.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body("Error interno"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        log.error("unexpected_request_failure requestId={}", MDC.get("requestId"), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body("Error interno"));
    }

    /**
     * El driver de PostgreSQL entrega "ERROR: mensaje" seguido de líneas "Where:"/"Detail:".
     * Se devuelve sólo el mensaje del RAISE, que en los triggers de Paktay está redactado para el usuario.
     */
    static String triggerMessage(String raw) {
        if (raw == null || raw.isBlank()) return "La operación no está permitida";
        String first = raw.strip().lines().findFirst().orElse("").strip();
        if (first.startsWith("ERROR:")) first = first.substring("ERROR:".length()).strip();
        return first.isEmpty() ? "La operación no está permitida" : first;
    }

    private static Map<String, String> body(String message) {
        return Map.of("message", message == null ? "Solicitud inválida" : message,
                "requestId", String.valueOf(MDC.get("requestId")));
    }
}
