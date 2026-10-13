package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

public record BusiestAirportDTO(
        String iataCode,
        String name,
        long routeCount
) {}