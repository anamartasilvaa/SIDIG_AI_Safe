package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import pt.isep.psoft.aisafe.flight_operations.domain.FlightSlot;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

import java.time.LocalDateTime;
public record ScheduledFlightViewDTO(
        String id,
        String routeId,
        String registrationNumber,
        String scheduledDateTime,
        String status,
        String actualDepartureDateTime,
        String actualArrivalDateTime
) {
    public static ScheduledFlightViewDTO from(ScheduledFlight f) {
        return new ScheduledFlightViewDTO(
                f.getFlightId().value(),
                f.getRouteId().value(),
                f.getAircraft().value(),
                f.getScheduledDateTime().toString(),
                f.getStatus().name(),
                f.getActualDepartureDateTime() == null ? null : f.getActualDepartureDateTime().toString(),
                f.getActualArrivalDateTime() == null ? null : f.getActualArrivalDateTime().toString());
    }
    public FlightSlot toSlot() {
        return new FlightSlot(id, LocalDateTime.parse(scheduledDateTime), FlightStatus.parse(status));
    }
}
