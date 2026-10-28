package pt.isep.psoft.aisafe.flight_operations.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledFlightTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);

    private ScheduledFlight newFlight() {
        return new ScheduledFlight(RouteId.of("OPO-LIS"), RegistrationNumber.of("CS-TTA"), NOW.plusDays(1), NOW);
    }

    @Test
    @DisplayName("A new flight starts as SCHEDULED and has a UUID id")
    void newFlightIsScheduled() {
        ScheduledFlight flight = newFlight();
        assertEquals(FlightStatus.SCHEDULED, flight.getStatus());
        assertNotNull(flight.getFlightId());
    }

    @Test
    @DisplayName("Cannot schedule in the past nor without mandatory data")
    void invariants() {
        assertThrows(IllegalArgumentException.class, () ->
                new ScheduledFlight(RouteId.of("A"), RegistrationNumber.of("B"), NOW.minusMinutes(1), NOW));
        assertThrows(IllegalArgumentException.class, () ->
                new ScheduledFlight(null, RegistrationNumber.of("B"), NOW.plusDays(1), NOW));
        assertThrows(IllegalArgumentException.class, () ->
                new ScheduledFlight(RouteId.of("A"), RegistrationNumber.of("B"), null, NOW));
    }

    @Test
    @DisplayName("Complete lifecycle records departure and arrival")
    void fullLifecycle() {
        ScheduledFlight flight = newFlight();
        flight.changeStatus(FlightStatus.DELAYED, NOW);
        flight.changeStatus(FlightStatus.IN_FLIGHT, NOW.plusHours(1));
        assertEquals(NOW.plusHours(1), flight.getActualDepartureDateTime());
        flight.changeStatus(FlightStatus.COMPLETED, NOW.plusHours(2));
        assertEquals(NOW.plusHours(2), flight.getActualArrivalDateTime());
        assertEquals(FlightStatus.COMPLETED, flight.getStatus());
    }

    @Test
    @DisplayName("Changing to the same status is idempotent (safe for retries)")
    void sameStatusIsNoOp() {
        ScheduledFlight flight = newFlight();
        flight.changeStatus(FlightStatus.DELAYED, NOW);
        assertDoesNotThrow(() -> flight.changeStatus(FlightStatus.DELAYED, NOW));
        assertEquals(FlightStatus.DELAYED, flight.getStatus());
    }

    @Test
    @DisplayName("Invalid transition throws SchedulingRuleViolation (409)")
    void invalidTransition() {
        ScheduledFlight flight = newFlight();
        flight.changeStatus(FlightStatus.IN_FLIGHT, NOW);
        assertThrows(SchedulingRuleViolation.class, () -> flight.changeStatus(FlightStatus.CANCELED, NOW));
        assertEquals(FlightStatus.IN_FLIGHT, flight.getStatus());
    }
}