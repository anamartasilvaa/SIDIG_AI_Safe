package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

import java.util.List;

public record AirportViewDTO(
        String iataCode,
        String name,
        String city,
        String country,
        String timezone,
        String type,
        String status,
        List<RunwayDTO> runways,
        List<FacilityDTO> facilities,

        String imageUrl,
        String operationalHours,
        String contactInformation
) {}