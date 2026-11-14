package pt.isep.psoft.aisafe.flight_operations.application.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.FlightViewRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.RouteUtilizationViewRepository;

import java.util.Optional;

/**
 * Updates the read model (schema fo_query) from the state carried by a flight event.
 * Idempotent and order-independent: an old or repeated event leaves the views unchanged.
 * Runs in the same transaction as the processing of the event.
 */
@Component
public class FlightProjector {

    private static final Logger log = LoggerFactory.getLogger(FlightProjector.class);

    private final FlightViewRepository flightViews;
    private final RouteUtilizationViewRepository routeUtilization;
    private final RouteRefRepository routeRefs;
    private final MoodProjector moods;

    public FlightProjector(FlightViewRepository flightViews, RouteUtilizationViewRepository routeUtilization,
                           RouteRefRepository routeRefs, MoodProjector moods) {
        this.flightViews = flightViews;
        this.routeUtilization = routeUtilization;
        this.routeRefs = routeRefs;
        this.moods = moods;
    }

    /** @return true if the views changed. */
    @Transactional
    public boolean project(FlightState state) {
        Optional<RouteRef> route = routeRefs.findById(state.routeId());
        String origin = route.map(RouteRef::getOrigin).orElse(null);
        String destination = route.map(RouteRef::getDestination).orElse(null);

        Optional<FlightView> existing = flightViews.findById(state.flightId());
        String previousAircraft = existing.map(FlightView::getRegistrationNumber).orElse(null);
        if (existing.isPresent()) {
            if (!existing.get().applyIfNewer(state, origin, destination)) {
                log.debug("Projection: flight {} rev {} ignored (view already at rev {}).",
                        state.flightId(), state.revision(), existing.get().getRevision());
                return false;
            }
        } else {
            flightViews.save(FlightView.from(state, origin, destination));
        }
        flightViews.flush();
        refreshRouteUtilization(state.routeId(), origin, destination);
        moods.recompute(state.registrationNumber());
        if (previousAircraft != null && !previousAircraft.equals(state.registrationNumber())) {
            moods.recompute(previousAircraft);   // reassigned: the old aircraft changes mood too
        }
        log.debug("Projection: flight {} -> rev {} ({}).", state.flightId(), state.revision(), state.status());
        return true;
    }

    /** Recomputed from flight_view (not incremented), so duplicates can never inflate it. */
    private void refreshRouteUtilization(String routeId, String origin, String destination) {
        long count = flightViews.countByRouteIdAndStatusNot(routeId, FlightStatus.CANCELED.name());
        RouteUtilizationView view = routeUtilization.findById(routeId)
                .orElseGet(() -> new RouteUtilizationView(routeId));
        view.update(count, origin, destination);
        routeUtilization.save(view);
    }
}
