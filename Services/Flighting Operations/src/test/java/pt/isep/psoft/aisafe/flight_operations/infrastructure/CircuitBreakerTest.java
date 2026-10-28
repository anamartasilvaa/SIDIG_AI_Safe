package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    private static final Duration OPEN_TIME = Duration.ofMillis(200);

    private CircuitBreaker openBreaker() {
        CircuitBreaker breaker = new CircuitBreaker("test", 3, OPEN_TIME);
        for (int i = 0; i < 3; i++) {
            assertTrue(breaker.tryAcquire());
            breaker.onFailure();
        }
        return breaker;
    }

    @Test
    @DisplayName("Opens after 3 consecutive failures and fails immediately")
    void opensAfterThreshold() {
        CircuitBreaker breaker = openBreaker();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire());
    }

    @Test
    @DisplayName("A success resets the failure count")
    void successResetsCount() {
        CircuitBreaker breaker = new CircuitBreaker("test", 3, OPEN_TIME);
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();
        breaker.onFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    @DisplayName("After the wait time, it allows ONE trial request to pass")
    void halfOpenAllowsSingleTrial() throws InterruptedException {
        CircuitBreaker breaker = openBreaker();
        Thread.sleep(OPEN_TIME.toMillis() + 50);
        assertTrue(breaker.tryAcquire());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertFalse(breaker.tryAcquire());
    }

    @Test
    @DisplayName("Success on the trial request closes the circuit")
    void trialSuccessCloses() throws InterruptedException {
        CircuitBreaker breaker = openBreaker();
        Thread.sleep(OPEN_TIME.toMillis() + 50);
        breaker.tryAcquire();
        breaker.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    @DisplayName("Failure on the trial request reopens the circuit")
    void trialFailureReopens() throws InterruptedException {
        CircuitBreaker breaker = openBreaker();
        Thread.sleep(OPEN_TIME.toMillis() + 50);
        breaker.tryAcquire();
        breaker.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }
}