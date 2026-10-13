package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

public record ScheduledFlightViewDTO(
        String id,
        String routeId,
        String registrationNumber,
        String scheduledDateTime,
        String status
) {
    public static ScheduledFlightViewDTO from(ScheduledFlight f) {
        return new ScheduledFlightViewDTO(
                f.getId(),
                f.getRouteId(),
                f.getAircraftRegistration(),
                f.getScheduledDateTime().toString(),
                f.getStatus().name());
    }
}
