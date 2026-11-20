package pt.isep.psoft.aisafe.flight_operations.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A replica without its database answers 503 + Retry-After (the gateway then tries the other replica). */
class GlobalExceptionHandlerDatabaseTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void transactionThatCannotStartIs503() {
        ResponseEntity<ProblemDetail> r = handler.databaseUnavailable(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));
        assertEquals(503, r.getStatusCode().value());
        assertEquals("1", r.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void lostConnectionIs503() {
        ResponseEntity<ProblemDetail> r = handler.databaseUnavailable(
                new DataAccessResourceFailureException("Connection refused"));
        assertEquals(503, r.getStatusCode().value());
    }
}
