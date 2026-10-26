package pt.isep.psoft.aisafe.aircraft.infrastructure;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {
    @Test
    void opensAfterFailuresAndAllowsTrialAfterTimeout() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofMillis(20));
        assertTrue(breaker.tryAcquire()); breaker.onFailure();
        assertTrue(breaker.tryAcquire()); breaker.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire());
        Thread.sleep(30);
        assertTrue(breaker.tryAcquire());
        breaker.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }
}
