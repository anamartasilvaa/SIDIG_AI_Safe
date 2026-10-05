package pt.isep.psoft.aisafe.flight_operations.application.DTO;

public record RouteUtilizationDTO(
        String routeId,
        Long flightCount
) {}