package ec.paktay.business.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst().map(error -> error.getField() + ": " + error.getDefaultMessage()).orElse("Solicitud inválida");
        log.warn("request_validation_failed requestId={} field={}", MDC.get("requestId"), message);
        return Map.of("message", message, "requestId", String.valueOf(MDC.get("requestId")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> business(IllegalArgumentException ex) {
        log.warn("request_rejected requestId={} reason={}", MDC.get("requestId"), ex.getMessage());
        return Map.of("message", ex.getMessage(), "requestId", String.valueOf(MDC.get("requestId")));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database(DataAccessException ex) {
        log.error("database_query_failed requestId={} reason={}", MDC.get("requestId"), ex.getMostSpecificCause().getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "Base de datos no disponible", "requestId", String.valueOf(MDC.get("requestId"))));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        log.error("unexpected_request_failure requestId={}", MDC.get("requestId"), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Error interno", "requestId", String.valueOf(MDC.get("requestId"))));
    }
}
