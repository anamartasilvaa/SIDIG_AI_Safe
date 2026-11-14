package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import pt.isep.psoft.aisafe.flight_operations.application.query.FlightView;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

/** JSON representation of a flight in the public API (same fields as P1, plus statusReason). */
public record ScheduledFlightViewDTO(
        String id,
        String routeId,
        String registrationNumber,
        String scheduledDateTime,
        String status,
        String actualDepartureDateTime,
        String actualArrivalDateTime,
        long revision,
        String lastModifiedAt,
        String statusReason
) {
    /** From the write model (response to a command). */
    public static ScheduledFlightViewDTO from(ScheduledFlight f) {
        return new ScheduledFlightViewDTO(
                f.getFlightId().value(),
                f.getRouteId().value(),
                f.getAircraft().value(),
                f.getScheduledDateTime().toString(),
                f.getStatus().name(),
                text(f.getActualDepartureDateTime()),
                text(f.getActualArrivalDateTime()),
                f.getRevision(),
                text(f.getLastModifiedAt()),
                f.getStatusReason());
    }

    /** From the read model (response to a query). */
    public static ScheduledFlightViewDTO from(FlightView v) {
        return new ScheduledFlightViewDTO(
                v.getFlightId(),
                v.getRouteId(),
                v.getRegistrationNumber(),
                v.getScheduledDateTime().toString(),
                v.getStatus(),
                text(v.getActualDeparture()),
                text(v.getActualArrival()),
                v.getRevision(),
                text(v.getLastModifiedAt()),
                v.getStatusReason());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
