package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteRequirements;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteInfo(
        @JsonAlias({"id", "routeCode"}) String routeId,
        @JsonAlias({"routeStatus"}) String status,
        Boolean active,
        @JsonAlias({"distanceKm"}) Double distance,
        @JsonAlias({"minRange"}) Double minimumRange,
        @JsonAlias({"minCapacity"}) Integer minimumCapacity
) {
    public RouteRequirements toRequirements(String requestedRouteId) {
        boolean isActive = status != null ? "ACTIVE".equalsIgnoreCase(status) : (active == null || active);
        return new RouteRequirements(routeId != null ? routeId : requestedRouteId,
                isActive, distance, minimumRange, minimumCapacity);
    }
}
