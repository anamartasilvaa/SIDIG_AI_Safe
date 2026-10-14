package pt.isep.psoft.aisafe.flight_operations.domain;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
public enum FlightStatus {
    SCHEDULED,
    DELAYED,
    IN_FLIGHT,
    COMPLETED,
    CANCELED;

    public Set<FlightStatus> allowedNext() {
        return switch (this) {
            case SCHEDULED -> EnumSet.of(DELAYED, IN_FLIGHT, CANCELED);
            case DELAYED -> EnumSet.of(IN_FLIGHT, CANCELED);
            case IN_FLIGHT -> EnumSet.of(COMPLETED);
            case COMPLETED, CANCELED -> EnumSet.noneOf(FlightStatus.class);
        };
    }

    public boolean canTransitionTo(FlightStatus next) {
        return allowedNext().contains(next);
    }

    public boolean occupiesAircraft() {
        return this != CANCELED;
    }

    public static FlightStatus parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Flight status is required.");
        }
        try {
            return FlightStatus.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown flight status '" + text + "'. Allowed: "
                    + EnumSet.allOf(FlightStatus.class));
        }
    }
}
