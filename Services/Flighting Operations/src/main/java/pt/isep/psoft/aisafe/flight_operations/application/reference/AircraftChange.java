package pt.isep.psoft.aisafe.flight_operations.application.reference;

import java.time.Instant;

/**
 * A change to an aircraft, published by the Aircraft Service and already translated to the
 * Flight Operations model (see AircraftEventTranslator). Null fields mean "not in this event".
 */
public record AircraftChange(
        String registrationNumber,
        String modelCode,
        String status,
        Double maximumRange,
        Integer seatingCapacity,
        long revision,
        Instant changedAt) {

    public AircraftChange {
        if (registrationNumber == null || registrationNumber.isBlank()) {
            throw new IllegalArgumentException("Aircraft registration is required.");
        }
        if (changedAt == null) {
            throw new IllegalArgumentException("The time of the change is required.");
        }
    }

    public boolean hasStatus() {
        return status != null && !status.isBlank();
    }
}
