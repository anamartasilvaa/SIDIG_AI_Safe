package pt.isep.psoft.aisafe.maintenance.infrastructure;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {
    @Test
    void halfOpenTrialClosesOnSuccess() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("aircraft", 1, Duration.ofMillis(1));
        assertTrue(breaker.tryAcquire()); breaker.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        Thread.sleep(3);
        assertTrue(breaker.tryAcquire());
        assertFalse(breaker.tryAcquire());
        breaker.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }
}
