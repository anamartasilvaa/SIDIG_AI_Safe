package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.PeerClient;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.util.Optional;

/**
 * A command (PATCH status) can reach a replica a few milliseconds before the FlightScheduled event
 * of that flight. Instead of answering 404, the replica fetches the flight once from a peer
 * (GET /internal/scheduled-flights/{id}) and applies it with the normal replica rules
 * (last-writer-wins), so the command can run. If no peer can answer, nothing changes (404 as before).
 */
@Component
public class MissingFlightRecovery {

    private static final Logger log = LoggerFactory.getLogger(MissingFlightRecovery.class);

    private final ScheduledFlightRepository flights;
    private final PeerClient peers;
    private final FlightReplicaService replicas;

    public MissingFlightRecovery(ScheduledFlightRepository flights, PeerClient peers, FlightReplicaService replicas) {
        this.flights = flights;
        this.peers = peers;
        this.replicas = replicas;
    }

    /** @return true if the flight was missing here and was copied from a peer. */
    public boolean fetchIfMissing(String flightId) {
        if (flights.findByFlightId_Uuid(flightId).isPresent()) {
            return false;
        }
        try {
            Optional<FlightState> remote = peers.findFirst("/internal/scheduled-flights/" + flightId, FlightState.class);
            if (remote.isEmpty()) {
                return false;
            }
            replicas.apply(remote.get(), "peer-fetch");
            log.info("Flight {} not yet replicated here: copied from a peer before running the command.", flightId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Flight {} not found locally and the peers could not provide it ({}).", flightId, e.getMessage());
            return false;
        }
    }
}
