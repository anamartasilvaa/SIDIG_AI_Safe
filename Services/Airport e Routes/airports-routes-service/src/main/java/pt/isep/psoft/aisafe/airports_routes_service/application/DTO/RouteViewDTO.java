package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

import java.time.Instant;

public record RouteViewDTO(
        String routeId,
        String originIata,
        String destinationIata,
        String status,
        Integer minimumCapacity,
        Instant lastModified,
        String originNodeId
) {
    public RouteViewDTO(String routeId, String originIata, String destinationIata,
                        String status, Integer minimumCapacity) {
        this(routeId, originIata, destinationIata, status, minimumCapacity, null, null);
    }
}