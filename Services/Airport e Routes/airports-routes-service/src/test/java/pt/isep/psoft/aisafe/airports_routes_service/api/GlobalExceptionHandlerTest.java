package pt.isep.psoft.aisafe.airports_routes_service.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.GlobalExceptionHandler;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Base de dados da instancia em baixo: 503, para a gateway passar o pedido a outra instancia. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void cannotOpenATransactionIs503() {
        ResponseEntity<Map<String, String>> r =
                handler.handleDatabaseUnavailable(new CannotCreateTransactionException("db down"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode());
        assertFalse(r.getBody().get("message").contains("db down"), "no internal detail leaked");
    }

    @Test
    void lostDatabaseConnectionIs503() {
        ResponseEntity<Map<String, String>> r =
                handler.handleDatabaseUnavailable(new DataAccessResourceFailureException("connection refused"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode());
    }
}
