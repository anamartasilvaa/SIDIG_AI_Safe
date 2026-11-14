package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import pt.isep.psoft.aisafe.flight_operations.application.query.AircraftMoodView;

/** One element of GET /api/v2/unicorns. */
public record UnicornDTO(
        String registrationNumber,
        String mood,
        String reason,
        int delayedOrCanceledLast7Days,
        int flightsNext24h,
        String updatedAt) {

    public static UnicornDTO from(AircraftMoodView v) {
        return new UnicornDTO(v.getRegistrationNumber(), v.getMood().name(), v.getReason(),
                v.getDelayedOrCanceled(), v.getFlightsNext24h(), v.getUpdatedAt().toString());
    }
}
