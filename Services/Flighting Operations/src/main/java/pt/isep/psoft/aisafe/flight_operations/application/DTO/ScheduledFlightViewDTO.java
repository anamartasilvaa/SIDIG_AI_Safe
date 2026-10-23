package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import pt.isep.psoft.aisafe.flight_operations.domain.FlightId;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightSlot;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;
import pt.isep.psoft.aisafe.flight_operations.domain.RegistrationNumber;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteId;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

import java.time.Instant;
import java.time.LocalDateTime;

public record ScheduledFlightViewDTO(
        String id,
        String routeId,
        String registrationNumber,
        String scheduledDateTime,
        String status,
        String actualDepartureDateTime,
        String actualArrivalDateTime,
        long revision,
        String lastModifiedAt
) {
    public static ScheduledFlightViewDTO from(ScheduledFlight f) {
        return new ScheduledFlightViewDTO(
                f.getFlightId().value(),
                f.getRouteId().value(),
                f.getAircraft().value(),
                f.getScheduledDateTime().toString(),
                f.getStatus().name(),
                f.getActualDepartureDateTime() == null ? null : f.getActualDepartureDateTime().toString(),
                f.getActualArrivalDateTime() == null ? null : f.getActualArrivalDateTime().toString(),
                f.getRevision(),
                f.getLastModifiedAt() == null ? null : f.getLastModifiedAt().toString());
    }

    public FlightSlot toSlot() {
        return new FlightSlot(id, LocalDateTime.parse(scheduledDateTime), FlightStatus.parse(status));
    }
    public ScheduledFlight toAggregate() {
        return ScheduledFlight.restore(FlightId.of(id), RouteId.of(routeId), RegistrationNumber.of(registrationNumber),
                LocalDateTime.parse(scheduledDateTime), parseOrNull(actualDepartureDateTime),
                parseOrNull(actualArrivalDateTime), FlightStatus.parse(status), revision, lastModified());
    }

    public Instant lastModified() {
        return lastModifiedAt == null ? Instant.EPOCH : Instant.parse(lastModifiedAt);
    }


    public static ScheduledFlightViewDTO newest(ScheduledFlightViewDTO a, ScheduledFlightViewDTO b) {
        if (a.revision != b.revision) return a.revision > b.revision ? a : b;
        int byTime = a.lastModified().compareTo(b.lastModified());
        if (byTime != 0) return byTime > 0 ? a : b;
        return FlightStatus.parse(a.status).ordinal() >= FlightStatus.parse(b.status).ordinal() ? a : b;
    }

    private static LocalDateTime parseOrNull(String value) {
        return value == null ? null : LocalDateTime.parse(value);
    }
}
