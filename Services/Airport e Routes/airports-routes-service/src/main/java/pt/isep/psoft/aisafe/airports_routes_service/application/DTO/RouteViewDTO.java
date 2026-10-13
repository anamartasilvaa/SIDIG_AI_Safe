package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

public record RouteViewDTO(
        String routeId,
        String originIata,
        String destinationIata,
        String status,
        Integer minimumCapacity
) {}