package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

import java.time.Instant;
import java.util.List;

/** Estado completo de um aeroporto (modelo de escrita), usado no snapshot entre instancias. */
public record AirportSnapshotDTO(
        String iataCode,
        String name,
        String city,
        String country,
        String timezone,
        String type,
        String status,
        Double latitude,
        Double longitude,
        List<RunwayDTO> runways,
        List<FacilityDTO> facilities,
        String imageUrl,
        String operationalHours,
        String contactInformation,
        Instant lastModified,
        String originNodeId
) {
}
