package pt.isep.psoft.aisafe.flight_operations.application.reference;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;

/**
 * Inserts a reference learned by a fallback lookup in its OWN short transaction.
 * Two requests that learn the same aircraft or route at the same time race on the primary key:
 * the loser gets a DataIntegrityViolationException that only rolls back this small transaction,
 * never the caller's (the command or the query that needed the reference).
 */
@Component
public class ReferenceWriter {

    private final AircraftRefRepository aircraft;
    private final RouteRefRepository routes;

    public ReferenceWriter(AircraftRefRepository aircraft, RouteRefRepository routes) {
        this.aircraft = aircraft;
        this.routes = routes;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertAircraft(AircraftRef ref) {
        aircraft.saveAndFlush(ref);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertRoute(RouteRef ref) {
        routes.saveAndFlush(ref);
    }
}
