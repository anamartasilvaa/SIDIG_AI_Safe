package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AircraftInfo;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteInfo;
import pt.isep.psoft.aisafe.flight_operations.application.reference.ReferenceLookup;
import pt.isep.psoft.aisafe.flight_operations.domain.AircraftCapabilities;
import pt.isep.psoft.aisafe.flight_operations.domain.RegistrationNumber;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteId;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteRequirements;

import java.util.Optional;

@Component
public class RestReferenceLookup implements ReferenceLookup {

    private final ReplicatedServiceClient routesClient;
    private final ReplicatedServiceClient aircraftClient;
    private final String routePath;
    private final String aircraftPath;

    public RestReferenceLookup(@Qualifier("routesClient") ReplicatedServiceClient routesClient,
                               @Qualifier("aircraftClient") ReplicatedServiceClient aircraftClient,
                               @Value("${app.services.routes.path}") String routePath,
                               @Value("${app.services.aircraft.path}") String aircraftPath) {
        this.routesClient = routesClient;
        this.aircraftClient = aircraftClient;
        this.routePath = routePath;
        this.aircraftPath = aircraftPath;
    }

    @Override
    public Optional<AircraftCapabilities> aircraft(RegistrationNumber registration) {
        return aircraftClient.get(aircraftPath.replace("{registration}", registration.value()), AircraftInfo.class)
                .map(info -> info.toCapabilities(registration.value()));
    }

    @Override
    public Optional<RouteRequirements> route(RouteId routeId) {
        return routesClient.get(routePath.replace("{id}", routeId.value()), RouteInfo.class)
                .map(info -> info.toRequirements(routeId.value()));
    }
}