package pt.isep.psoft.aisafe.aircraft.infrastructure;

import java.time.Duration;
import java.time.Instant;

/** Small dependency-free circuit breaker used per remote host. */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }
    private final int failureThreshold;
    private final Duration openDuration;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean trialInFlight;

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration;
    }
    public synchronized boolean tryAcquire() {
        if (state == State.OPEN) {
            if (Instant.now().isBefore(openedAt.plus(openDuration))) return false;
            state = State.HALF_OPEN;
            trialInFlight = false;
        }
        if (state == State.HALF_OPEN && trialInFlight) return false;
        if (state == State.HALF_OPEN) trialInFlight = true;
        return true;
    }
    public synchronized void onSuccess() {
        state = State.CLOSED; consecutiveFailures = 0; trialInFlight = false;
    }
    public synchronized void onFailure() {
        trialInFlight = false;
        if (state == State.HALF_OPEN || ++consecutiveFailures >= failureThreshold) {
            state = State.OPEN; openedAt = Instant.now(); consecutiveFailures = 0;
        }
    }
    public synchronized State state() { return state; }
}
