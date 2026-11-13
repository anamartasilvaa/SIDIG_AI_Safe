package pt.isep.psoft.aisafe.flight_operations.application.reference;

import pt.isep.psoft.aisafe.flight_operations.domain.AircraftCapabilities;
import pt.isep.psoft.aisafe.flight_operations.domain.RegistrationNumber;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteId;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteRequirements;

import java.util.Optional;
public interface ReferenceLookup {

    Optional<AircraftCapabilities> aircraft(RegistrationNumber registration);

    Optional<RouteRequirements> route(RouteId routeId);
}