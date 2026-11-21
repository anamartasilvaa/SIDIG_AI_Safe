package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

import java.time.Instant;

/** Estado completo de uma rota (modelo de escrita), usado no snapshot entre instancias. */
public record RouteSnapshotDTO(
        String routeId,
        String originIata,
        String destinationIata,
        Integer estimatedFlightTime,
        Double minimumRange,
        Integer minimumCapacity,
        String status,
        Instant lastModified,
        String originNodeId
) {
}
