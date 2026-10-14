package pt.isep.psoft.aisafe.flight_operations.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

public class FlightSchedulingPolicy {

    private final Duration minimumGap;

    public FlightSchedulingPolicy(Duration minimumGap) {
        if (minimumGap == null || minimumGap.isNegative()) {
            throw new IllegalArgumentException("Minimum gap between flights must be zero or positive.");
        }
        this.minimumGap = minimumGap;
    }

    public void checkAssignment(RouteRequirements route, AircraftCapabilities aircraft) {
        if (!route.active()) {
            throw new SchedulingRuleViolation("Route " + route.routeId() + " is not active.");
        }
        if (!aircraft.isOperational()) {
            throw new SchedulingRuleViolation("Aircraft " + aircraft.registrationNumber()
                    + " is " + aircraft.status() + " and cannot be assigned to a flight.");
        }
        Double required = route.requiredRange();
        if (required != null && aircraft.maximumRange() != null && aircraft.maximumRange() < required) {
            throw new SchedulingRuleViolation(String.format(
                    "Aircraft %s range (%.0f) is below the range required by route %s (%.0f).",
                    aircraft.registrationNumber(), aircraft.maximumRange(), route.routeId(), required));
        }
        if (route.minimumCapacity() != null && aircraft.seatingCapacity() != null
                && aircraft.seatingCapacity() < route.minimumCapacity()) {
            throw new SchedulingRuleViolation(String.format(
                    "Aircraft %s capacity (%d) is below the minimum required by route %s (%d).",
                    aircraft.registrationNumber(), aircraft.seatingCapacity(), route.routeId(),
                    route.minimumCapacity()));
        }
    }

    public Optional<FlightSlot> findConflict(Collection<FlightSlot> existing, LocalDateTime when) {
        return existing.stream()
                .filter(slot -> slot.status().occupiesAircraft())
                .filter(slot -> Duration.between(slot.scheduledDateTime(), when).abs().compareTo(minimumGap) < 0)
                .min(Comparator.comparing(slot -> Duration.between(slot.scheduledDateTime(), when).abs()));
    }

    public Duration minimumGap() {
        return minimumGap;
    }
}
