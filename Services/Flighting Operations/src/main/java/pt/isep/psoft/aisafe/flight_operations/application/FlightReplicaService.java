package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.application.query.FlightProjector;
import pt.isep.psoft.aisafe.flight_operations.domain.*;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.util.Optional;

/**
 * Applies a flight event (from ANY replica, this one included) to this replica:
 * 1) command store: the copy of the aggregate, last-writer-wins by revision;
 * 2) query store: the views (FlightProjector);
 * 3) conflicts created during a partition (ConflictResolver).
 * All in one transaction, with the aircraft row locked like the command side does.
 */
@Service
public class FlightReplicaService {

    private static final Logger log = LoggerFactory.getLogger(FlightReplicaService.class);

    private final ScheduledFlightRepository flights;
    private final AircraftRefRepository aircraftRefs;
    private final FlightProjector projector;
    private final ConflictResolver conflicts;

    public FlightReplicaService(ScheduledFlightRepository flights, AircraftRefRepository aircraftRefs,
                                FlightProjector projector, ConflictResolver conflicts) {
        this.flights = flights;
        this.aircraftRefs = aircraftRefs;
        this.projector = projector;
        this.conflicts = conflicts;
    }

    /** @return true if the command-side copy changed. */
    @Transactional
    public boolean apply(FlightState s, String correlationId) {
        // Same lock order as FlightCommandService: serialises with local schedules of this aircraft.
        aircraftRefs.findForUpdate(s.registrationNumber());

        FlightStatus status = FlightStatus.parse(s.status());
        RegistrationNumber aircraft = RegistrationNumber.of(s.registrationNumber());
        Optional<ScheduledFlight> existing = flights.findByFlightId_Uuid(s.flightId());

        boolean changed;
        ScheduledFlight flight;
        if (existing.isEmpty()) {
            flight = flights.save(ScheduledFlight.restore(FlightId.of(s.flightId()), RouteId.of(s.routeId()),
                    aircraft, s.scheduledDateTime(), s.actualDepartureDateTime(), s.actualArrivalDateTime(),
                    status, s.statusReason(), s.revision(), s.lastModifiedAt(), s.createdAt(), s.originInstance()));
            changed = true;
            log.info("REPLICA: flight {} added (rev {}, {}, from {}).", s.flightId(), s.revision(), status,
                    s.originInstance());
        } else {
            flight = existing.get();
            changed = flight.mergeReplica(aircraft, status, s.statusReason(), s.actualDepartureDateTime(),
                    s.actualArrivalDateTime(), s.revision(), s.lastModifiedAt());
            if (changed) {
                flights.save(flight);
                log.info("REPLICA: flight {} updated to rev {} ({}).", s.flightId(), s.revision(), status);
            }
        }

        projector.project(s);

        if (changed) {
            conflicts.resolve(flight, correlationId);
        }
        return changed;
    }
}
