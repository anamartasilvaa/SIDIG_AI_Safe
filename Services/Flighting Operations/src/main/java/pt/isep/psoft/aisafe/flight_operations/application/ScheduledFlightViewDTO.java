package pt.isep.psoft.aisafe.flight_operations.application;

public record ScheduledFlightViewDTO(
        Long id,
        String routeId,
        String registrationNumber,
        String scheduledDateTime,
        String status
) {}