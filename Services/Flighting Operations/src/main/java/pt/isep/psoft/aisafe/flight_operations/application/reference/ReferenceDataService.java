package pt.isep.psoft.aisafe.flight_operations.application.reference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;

import java.util.Optional;

@Service
public class ReferenceDataService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataService.class);

    private final RouteRefRepository routes;

    public ReferenceDataService(RouteRefRepository routes) {
        this.routes = routes;
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
}