package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

public record RunwayDTO(
        String name,
        Double length,
        String orientation
) {}