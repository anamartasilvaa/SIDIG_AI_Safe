package pt.isep.psoft.aisafe.flight_operations.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

import java.time.Instant;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightState(
        String flightId,
        String routeId,
        String registrationNumber,
        LocalDateTime scheduledDateTime,
        LocalDateTime actualDepartureDateTime,
        LocalDateTime actualArrivalDateTime,
        String status,
        String statusReason,
        long revision,
        Instant lastModifiedAt,
        Instant createdAt,
        String originInstance) {

    public static FlightState of(ScheduledFlight f) {
        return new FlightState(
                f.getFlightId().value(),
                f.getRouteId().value(),
                f.getAircraft().value(),
                f.getScheduledDateTime(),
                f.getActualDepartureDateTime(),
                f.getActualArrivalDateTime(),
                f.getStatus().name(),
                f.getStatusReason(),
                f.getRevision(),
                f.getLastModifiedAt(),
                f.getCreatedAt(),
                f.getOriginInstance());
    }
}