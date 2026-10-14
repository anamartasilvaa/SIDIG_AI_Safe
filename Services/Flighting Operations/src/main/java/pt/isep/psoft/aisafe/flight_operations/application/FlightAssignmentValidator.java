package pt.isep.psoft.aisafe.flight_operations.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AircraftInfo;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteInfo;
import pt.isep.psoft.aisafe.flight_operations.domain.*;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.ReplicatedServiceClient;

import java.util.Optional;

@Component
public class FlightAssignmentValidator {

    private final ReplicatedServiceClient routesClient;
    private final ReplicatedServiceClient aircraftClient;
    private final FlightSchedulingPolicy policy;
    private final String routePath;
    private final String aircraftPath;

    public FlightAssignmentValidator(@Qualifier("routesClient") ReplicatedServiceClient routesClient,
                                     @Qualifier("aircraftClient") ReplicatedServiceClient aircraftClient,
                                     FlightSchedulingPolicy policy,
                                     @Value("${app.services.routes.path}") String routePath,
                                     @Value("${app.services.aircraft.path}") String aircraftPath) {
        this.routesClient = routesClient;
        this.aircraftClient = aircraftClient;
        this.policy = policy;
        this.routePath = routePath;
        this.aircraftPath = aircraftPath;
    }

    public void validate(RouteId routeId, RegistrationNumber registration) {
        RouteRequirements route = findRoute(routeId)
                .orElseThrow(() -> new SchedulingRuleViolation("Route " + routeId + " does not exist."));
        AircraftCapabilities aircraft = findAircraft(registration)
                .orElseThrow(() -> new SchedulingRuleViolation("Aircraft " + registration + " does not exist."));
        policy.checkAssignment(route, aircraft);
    }

    public Optional<RouteRequirements> findRoute(RouteId routeId) {
        return routesClient.get(routePath.replace("{id}", routeId.value()), RouteInfo.class)
                .map(info -> info.toRequirements(routeId.value()));
    }

    public Optional<AircraftCapabilities> findAircraft(RegistrationNumber registration) {
        return aircraftClient.get(aircraftPath.replace("{registration}", registration.value()), AircraftInfo.class)
                .map(info -> info.toCapabilities(registration.value()));
    }
}
