package pt.isep.psoft.aisafe.flight_operations.application.reference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

/**
 * Keeps the local copies of other services' data (reference data used by the command side).
 * Every method is idempotent: applying the same change twice leaves the same state.
 * The fallback inserts are also safe under concurrency (see {@link ReferenceWriter}).
 */
@Service
public class ReferenceDataService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataService.class);

    private final RouteRefRepository routes;
    private final AircraftRefRepository aircraft;
    private final ReferenceLookup lookup;
    private final ReferenceWriter writer;
    private final Clock clock;

    public ReferenceDataService(RouteRefRepository routes, AircraftRefRepository aircraft,
                                ReferenceLookup lookup, ReferenceWriter writer, Clock clock) {
        this.routes = routes;
        this.aircraft = aircraft;
        this.lookup = lookup;
        this.writer = writer;
        this.clock = clock;
    }

    /** @return true if the local copy changed. */
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
        // First time this route is seen. A route only updated or deactivated (creation missed)
        // is still stored; the startup snapshot fills any missing fields.
        boolean active = change.active() == null || change.active();
        routes.save(RouteRef.create(change.routeId(), change.origin(), change.destination(), active,
                null, change.minimumRange(), change.minimumCapacity(), 0, change.changedAt()));
        log.info("Route {} added to the local references (active={}).", change.routeId(), active);
        return true;
    }

    /**
     * Makes sure the aircraft is in the local copy. If it is not, asks the Aircraft Service ONCE
     * and stores the answer; later changes arrive by events.
     * @return false if the aircraft does not exist in the Aircraft Service.
     * @throws pt.isep.psoft.aisafe.flight_operations.infrastructure.DependencyUnavailableException
     *         only if the aircraft is unknown locally AND the Aircraft Service is down.
     * Not transactional on purpose: the insert runs in its own transaction (ReferenceWriter), so a
     * failed lookup or a lost insert race never marks the caller's transaction for rollback.
     */
    public boolean ensureAircraft(RegistrationNumber registration) {
        if (aircraft.existsById(registration.value())) {
            return true;
        }
        Optional<AircraftCapabilities> remote = lookup.aircraft(registration);
        if (remote.isEmpty()) {
            return false;
        }
        AircraftCapabilities a = remote.get();
        try {
            writer.insertAircraft(AircraftRef.create(registration.value(), null,
                    a.status() == null ? AircraftRef.ACTIVE : a.status(),
                    a.maximumRange(), a.seatingCapacity(), 0, clock.instant()));
            log.info("Aircraft {} added to the local references (fallback lookup, status {}).",
                    registration, a.status());
        } catch (DataIntegrityViolationException alreadyThere) {
            // A concurrent request stored it first: the reference exists, which is all we need.
            log.debug("Aircraft {} was added concurrently by another request.", registration);
        }
        return true;
    }

    /** Same as {@link #ensureAircraft} for routes. */
    public boolean ensureRoute(RouteId routeId) {
        if (routes.existsById(routeId.value())) {
            return true;
        }
        Optional<RouteRequirements> remote = lookup.route(routeId);
        if (remote.isEmpty()) {
            return false;
        }
        RouteRequirements r = remote.get();
        try {
            writer.insertRoute(RouteRef.create(routeId.value(), null, null, r.active(), r.distance(),
                    r.minimumRange(), r.minimumCapacity(), 0, clock.instant()));
            log.info("Route {} added to the local references (fallback lookup, active={}).", routeId, r.active());
        } catch (DataIntegrityViolationException alreadyThere) {
            log.debug("Route {} was added concurrently by another request.", routeId);
        }
        return true;
    }
}
