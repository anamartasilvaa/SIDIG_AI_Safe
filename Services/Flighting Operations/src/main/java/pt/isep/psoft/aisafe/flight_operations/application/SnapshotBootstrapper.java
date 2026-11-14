package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.PeerClient;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Bootstrap of a replica that starts with an EMPTY database (disk lost or new replica).
 * The queues only hold the events published after they were created, so the flights that
 * already existed are copied once from a peer (GET /internal/scheduled-flights).
 * - runs only if the command store is empty and there are peers;
 * - runs in the background, so the replica starts and consumes its queues at the same time;
 * - every flight goes through FlightReplicaService (last-writer-wins by revision), so a
 *   snapshot older than an event already consumed changes nothing;
 * - if no peer answers, tries again (retry-interval, max-attempts).
 */
@Component
public class SnapshotBootstrapper {

    static final String SNAPSHOT_PATH = "/internal/scheduled-flights";
    private static final Logger log = LoggerFactory.getLogger(SnapshotBootstrapper.class);
    private static final ParameterizedTypeReference<List<FlightState>> FLIGHT_LIST =
            new ParameterizedTypeReference<>() {};

    private final ScheduledFlightRepository flights;
    private final PeerClient peers;
    private final FlightReplicaService replicas;
    private final Duration retryInterval;
    private final int maxAttempts;

    public SnapshotBootstrapper(ScheduledFlightRepository flights, PeerClient peers,
                                FlightReplicaService replicas,
                                @Value("${app.bootstrap.retry-interval:5s}") Duration retryInterval,
                                @Value("${app.bootstrap.max-attempts:24}") int maxAttempts) {
        this.flights = flights;
        this.peers = peers;
        this.replicas = replicas;
        this.retryInterval = retryInterval;
        this.maxAttempts = maxAttempts;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (flights.count() > 0) {
            log.info("BOOTSTRAP: local database already has flights; snapshot not needed.");
            return;
        }
        Thread worker = new Thread(this::run, "fo-snapshot-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    void run() {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (bootstrapOnce()) {
                return;
            }
            log.warn("BOOTSTRAP: no peer answered (attempt {}/{}); trying again in {}.",
                    attempt, maxAttempts, retryInterval);
            try {
                Thread.sleep(retryInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.error("BOOTSTRAP: gave up after {} attempts; only new events will be received.", maxAttempts);
    }

    /** @return true when at least one peer answered (even with no flights), false to try again. */
    boolean bootstrapOnce() {
        AggregatedResult<FlightState> result = peers.collectAll(SNAPSHOT_PATH, FLIGHT_LIST);
        if (result.items().isEmpty() && result.isPartial()) {
            return false;
        }
        List<FlightState> snapshot = newestPerFlight(result.items());
        int applied = 0;
        for (FlightState state : snapshot) {
            try {
                if (replicas.apply(state, "bootstrap")) {
                    applied++;
                }
            } catch (RuntimeException e) {
                log.warn("BOOTSTRAP: flight {} not applied ({}).", state.flightId(), e.getMessage());
            }
        }
        log.info("BOOTSTRAP: {} flights received, {} applied (unreachable peers: {}).",
                snapshot.size(), applied, result.unreachablePeers());
        return true;
    }

    /** One entry per flight (the highest revision), oldest first: same order on every replica. */
    static List<FlightState> newestPerFlight(List<FlightState> items) {
        Map<String, FlightState> newest = new HashMap<>();
        for (FlightState s : items) {
            if (s == null || s.flightId() == null) {
                continue;
            }
            newest.merge(s.flightId(), s, (a, b) -> b.revision() > a.revision() ? b : a);
        }
        List<FlightState> ordered = new ArrayList<>(newest.values());
        ordered.sort(Comparator
                .comparing((FlightState s) -> Optional.ofNullable(s.createdAt()).orElse(Instant.MAX))
                .thenComparing(FlightState::flightId));
        return ordered;
    }
}
