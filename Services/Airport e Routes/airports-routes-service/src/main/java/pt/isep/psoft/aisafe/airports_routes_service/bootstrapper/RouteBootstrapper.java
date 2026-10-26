package pt.isep.psoft.aisafe.airports_routes_service.bootstrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.CreateRouteDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.NotFoundException;

@Component
@Order(4)
public class RouteBootstrapper implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RouteBootstrapper.class);
    private final RouteService routeService;

    public RouteBootstrapper(RouteService routeService) {
        this.routeService = routeService;
    }

    @Override
    public void run(String... args) {
        saveRoute("RT-LISLHR", "LIS", "LHR", 140, 1500.0, 150);
        saveRoute("RT-LISOPO", "LIS", "OPO", 45, 250.0, 70);
        saveRoute("RT-OPOLHR", "OPO", "LHR", 130, 1330.0, 120);
        saveRoute("RT-OPOLIS", "OPO", "LIS", 45, 250.0, 70);
        saveRoute("RT-LISJFK", "LIS", "JFK", 480, 5000.0, 150);
        saveRoute("RT-JFKLIS", "JFK", "LIS", 480, 5000.0, 150);
        saveRoute("RT-LISCDG", "LIS", "CDG", 150, 1530.0, 150);
        saveRoute("RT-CDGLIS", "CDG", "LIS", 155, 1530.0, 150);
        saveRoute("RT-LHROPO", "LHR", "OPO", 135, 1330.0, 120);
        saveRoute("RT-LHRJFK", "LHR", "JFK", 460, 5540.0, 250);
        saveRoute("RT-CDGJFK", "CDG", "JFK", 490, 5830.0, 250);
    }

    private void saveRoute(String routeId, String origin, String destination,
                           int estimatedFlightTime, double minimumRange, int minimumCapacity) {
        try {
            // Routes are created through the local service only; no external domain is needed.
            routeService.createRoute(new CreateRouteDTO(
                    routeId, origin, destination, estimatedFlightTime, minimumRange, minimumCapacity), true);
        } catch (IllegalArgumentException | NotFoundException exception) {
            log.debug("Route {} already exists or has invalid airport references", routeId, exception);
        }
    }
}
