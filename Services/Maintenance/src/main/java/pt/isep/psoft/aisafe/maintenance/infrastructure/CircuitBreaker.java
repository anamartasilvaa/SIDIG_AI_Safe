package pt.isep.psoft.aisafe.maintenance.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

public class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);
    private final String target;
    private final int failureThreshold;
    private final Duration openDuration;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean trialInFlight;

    public CircuitBreaker(String target, int failureThreshold, Duration openDuration) {
        this.target = target;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration;
    }

    public synchronized boolean tryAcquire() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (Instant.now().isBefore(openedAt.plus(openDuration))) {
                return false;
            }
            state = State.HALF_OPEN;
            trialInFlight = false;
            log.info("Circuit {} -> HALF_OPEN", target);
        }
        if (trialInFlight) {
            return false;
        }
        trialInFlight = true;
        return true;
    }

    public synchronized void onSuccess() {
        if (state != State.CLOSED) {
            log.info("Circuit {} -> CLOSED", target);
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
        trialInFlight = false;
    }

    public synchronized void onFailure() {
        trialInFlight = false;
        if (state == State.HALF_OPEN || ++consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = Instant.now();
            consecutiveFailures = 0;
            log.warn("Circuit {} -> OPEN for {}s", target, openDuration.toSeconds());
        }
    }

    public synchronized State state() {
        return state;
    }
}
