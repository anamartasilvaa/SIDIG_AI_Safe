package pt.isep.psoft.aisafe.flight_operations.application.reference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.domain.AircraftCapabilities;
import pt.isep.psoft.aisafe.flight_operations.domain.RegistrationNumber;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteId;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteRequirements;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;

import java.time.Clock;
import java.util.Optional;

@Service
public class ReferenceDataService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataService.class);

    private final RouteRefRepository routes;
    private final AircraftRefRepository aircraft;
    private final ReferenceLookup lookup;
    private final Clock clock;

    public ReferenceDataService(RouteRefRepository routes, AircraftRefRepository aircraft,
                                ReferenceLookup lookup, Clock clock) {
        this.routes = routes;
        this.aircraft = aircraft;
        this.lookup = lookup;
        this.clock = clock;
    }

    @Transactional
    public boolean applyRouteChange(RouteChange change) {
        Optional<RouteRef> existing = routes.findById(change.routeId());
        if (existing.isPresent()) {
            boolean changed = existing.get().applyIfNewer(change.origin(), change.destination(), change.active(),
                    null, change.minimumRange(), change.minimumCapacity(), 0, change.changedAt());
            if (!changed) {
                log.debug("Route {}: change ignored (not newer than the local copy).", change.routeId());
            }
            return changed;
        }
        boolean active = change.active() == null || change.active();
        routes.save(RouteRef.create(change.routeId(), change.origin(), change.destination(), active,
                null, change.minimumRange(), change.minimumCapacity(), 0, change.changedAt()));
        log.info("Route {} added to the local references (active={}).", change.routeId(), active);
        return true;
    }

    @Transactional
    public boolean ensureAircraft(RegistrationNumber registration) {
        if (aircraft.existsById(registration.value())) {
            return true;
        }
        Optional<AircraftCapabilities> remote = lookup.aircraft(registration);
        if (remote.isEmpty()) {
            return false;
        }
        AircraftCapabilities a = remote.get();
        aircraft.save(AircraftRef.create(registration.value(), null,
                a.status() == null ? AircraftRef.ACTIVE : a.status(),
                a.maximumRange(), a.seatingCapacity(), 0, clock.instant()));
        log.info("Aircraft {} added to the local references (fallback lookup, status {}).",
                registration, a.status());
        return true;
    }

    @Transactional
    public boolean ensureRoute(RouteId routeId) {
        if (routes.existsById(routeId.value())) {
            return true;
        }
        Optional<RouteRequirements> remote = lookup.route(routeId);
        if (remote.isEmpty()) {
            return false;
        }
        RouteRequirements r = remote.get();
        routes.save(RouteRef.create(routeId.value(), null, null, r.active(), r.distance(),
                r.minimumRange(), r.minimumCapacity(), 0, clock.instant()));
        log.info("Route {} added to the local references (fallback lookup, active={}).", routeId, r.active());
        return true;
    }
}