package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.PeerClient;

import java.util.List;

/**
 * Anti-entropy: every app.sync.interval this replica compares itself with its peers and applies
 * any flight state it is missing or has in an older revision.
 * Why: the queues deliver every event, but an event can still be lost for THIS replica if it
 * keeps failing here (for example, the replica is running while its own database is down: after
 * x-delivery-limit attempts the message goes to the dead-letter queue). This job repairs such
 * gaps without manual work.
 * - same path and rules as the startup snapshot (GET /internal/scheduled-flights, last-writer-wins
 *   by revision), so applying the same state twice or an older state changes nothing;
 * - if this replica's database or every peer is unavailable, the round is skipped and retried later.
 */
@Component
public class ReplicaSyncReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReplicaSyncReconciler.class);
    private static final ParameterizedTypeReference<List<FlightState>> FLIGHT_LIST =
            new ParameterizedTypeReference<>() {};

    private final PeerClient peers;
    private final FlightReplicaService replicas;

    public ReplicaSyncReconciler(PeerClient peers, FlightReplicaService replicas) {
        this.peers = peers;
        this.replicas = replicas;
    }

    @Scheduled(fixedDelayString = "${app.sync.interval:20s}", initialDelayString = "${app.sync.interval:20s}")
    public void reconcile() {
        syncOnce();
    }

    /** @return number of flights that were missing or older here and have been applied. */
    int syncOnce() {
        AggregatedResult<FlightState> result;
        try {
            result = peers.collectAll(SnapshotBootstrapper.SNAPSHOT_PATH, FLIGHT_LIST);
        } catch (RuntimeException e) {
            log.debug("SYNC: peers not reachable ({}); next round later.", e.getMessage());
            return 0;
        }
        int applied = 0;
        for (FlightState state : SnapshotBootstrapper.newestPerFlight(result.items())) {
            try {
                if (replicas.apply(state, "sync")) {
                    applied++;
                }
            } catch (RuntimeException e) {
                // Typically this replica's own database is down: stop this round, try the next one.
                log.debug("SYNC: flight {} not applied ({}); next round later.", state.flightId(), e.getMessage());
                return applied;
            }
        }
        if (applied > 0) {
            log.info("SYNC: {} flight(s) were missing or older on this replica and have been repaired.", applied);
        }
        return applied;
    }
}
