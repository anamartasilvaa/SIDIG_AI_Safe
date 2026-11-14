package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.application.reference.AircraftChange;
import pt.isep.psoft.aisafe.flight_operations.application.reference.AircraftReferenceService;

import java.util.List;

/**
 * Handles the messages of the shared work queue fo.grounding (each message is processed by ONE
 * replica; the reassignments then reach the other replica as normal flight events).
 * - AircraftStatusChanged: updates the local copy first and grounds the aircraft only if, after
 *   that, it is known and not ACTIVE (an old event cannot ground an aircraft that is ACTIVE again);
 * - GroundingRequested: grounds the aircraft.
 * GroundingService is idempotent, so a redelivery (or the REST endpoint called as well) moves
 * nothing the second time.
 */
@Service
public class AircraftGroundingHandler {

    private static final Logger log = LoggerFactory.getLogger(AircraftGroundingHandler.class);

    private final AircraftReferenceService references;
    private final GroundingService grounding;

    public AircraftGroundingHandler(AircraftReferenceService references, GroundingService grounding) {
        this.references = references;
        this.grounding = grounding;
    }

    /** @return the grounding report, or an empty list if the aircraft is ACTIVE. */
    @Transactional
    public List<String> onAircraftStatus(AircraftChange change, String correlationId) {
        if (!change.hasStatus()) {
            return List.of();
        }
        references.apply(change);
        if (!references.isGrounded(change.registrationNumber())) {
            log.info("Aircraft {} is ACTIVE: no grounding needed.", change.registrationNumber());
            return List.of();
        }
        return grounding.ground(change.registrationNumber(), correlationId);
    }

    @Transactional
    public List<String> onGroundingRequested(String registration, String correlationId) {
        return grounding.ground(registration, correlationId);
    }
}
