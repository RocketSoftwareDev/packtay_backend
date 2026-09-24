package ec.paktay.business.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;

/** Pruebas unitarias puras del mapeo de errores de base de datos a HTTP. */
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void triggerRuleIsAConflictWithItsOwnMessage() {
        SQLException raised = new SQLException(
                "ERROR: No se puede modificar un gasto de un período cerrado\n  Where: PL/pgSQL function protect_expense_update()",
                ApiExceptionHandler.PLPGSQL_RAISE_SQLSTATE);
        var response = handler.database(new UncategorizedSQLException("update", "update expenses", raised));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("No se puede modificar un gasto de un período cerrado", response.getBody().get("message"));
    }

    @Test
    void connectivityFailureStaysServiceUnavailable() {
        var response = handler.database(new DataAccessResourceFailureException("sin conexión"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void constraintViolationIsAGenericConflict() {
        var body = handler.integrity(new DataIntegrityViolationException("duplicate key"));
        assertEquals("Conflicto con datos existentes", body.get("message"));
    }

    @Test
    void triggerMessageWithoutPrefixIsKept() {
        assertEquals("Un gasto anulado no se puede reactivar",
                ApiExceptionHandler.triggerMessage("Un gasto anulado no se puede reactivar"));
        assertEquals("La operación no está permitida", ApiExceptionHandler.triggerMessage(null));
    }
}
