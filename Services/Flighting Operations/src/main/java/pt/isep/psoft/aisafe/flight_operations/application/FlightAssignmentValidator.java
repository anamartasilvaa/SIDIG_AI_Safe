package pt.isep.psoft.aisafe.flight_operations.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AircraftInfo;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteInfo;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.ReplicatedServiceClient;

/**
 * PASSO 6 - "Aircraft-to-route assignment under range and capacity constraints".
 * Consulta os outros dois servicos (por HTTP) antes de aceitar um voo.
 */
@Component
public class FlightAssignmentValidator {

    private final ReplicatedServiceClient routesClient;
    private final ReplicatedServiceClient aircraftClient;
    private final String routePath;
    private final String aircraftPath;

    public FlightAssignmentValidator(@Qualifier("routesClient") ReplicatedServiceClient routesClient,
                                     @Qualifier("aircraftClient") ReplicatedServiceClient aircraftClient,
                                     @Value("${app.services.routes.path}") String routePath,
                                     @Value("${app.services.aircraft.path}") String aircraftPath) {
        this.routesClient = routesClient;
        this.aircraftClient = aircraftClient;
        this.routePath = routePath;
        this.aircraftPath = aircraftPath;
    }

    public void validate(String routeId, String registration) {
        RouteInfo route = routesClient
                .get(routePath.replace("{id}", routeId), RouteInfo.class)
                .orElseThrow(() -> new BusinessRuleException("Route " + routeId + " does not exist."));

        if (Boolean.FALSE.equals(route.active())) {
            throw new BusinessRuleException("Route " + routeId + " is not active.");
        }

        AircraftInfo aircraft = aircraftClient
                .get(aircraftPath.replace("{registration}", registration), AircraftInfo.class)
                .orElseThrow(() -> new BusinessRuleException("Aircraft " + registration + " does not exist."));

        if (aircraft.status() != null && !"ACTIVE".equalsIgnoreCase(aircraft.status())) {
            throw new BusinessRuleException("Aircraft " + registration + " is " + aircraft.status() + ".");
        }

        if (route.distanceKm() != null && aircraft.rangeKm() != null
                && aircraft.rangeKm() < route.distanceKm()) {
            throw new BusinessRuleException(String.format(
                    "Aircraft range (%.0f km) is shorter than route distance (%.0f km).",
                    aircraft.rangeKm(), route.distanceKm()));
        }

        if (route.minCapacity() != null && aircraft.capacity() != null
                && aircraft.capacity() < route.minCapacity()) {
            throw new BusinessRuleException(String.format(
                    "Aircraft capacity (%d) is below route minimum (%d).",
                    aircraft.capacity(), route.minCapacity()));
        }
    }
}
