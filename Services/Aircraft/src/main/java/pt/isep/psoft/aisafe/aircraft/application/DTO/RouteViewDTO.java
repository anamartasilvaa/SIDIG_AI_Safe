package pt.isep.psoft.aisafe.aircraft.application.DTO;

public record RouteViewDTO(
        String routeId,
        String originIata,
        String destinationIata,
        String status,
        Integer minimumCapacity,
        Double minimumRange
) {}
