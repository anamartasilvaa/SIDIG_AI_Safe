package pt.isep.psoft.aisafe.flight_operations.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AircraftReassignmentTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);
    private static final RegistrationNumber TTA = RegistrationNumber.of("CS-TTA");
    private static final RegistrationNumber TTB = RegistrationNumber.of("CS-TTB");

    private ScheduledFlight flight() {
        return new ScheduledFlight(RouteId.of("RT-OPOLIS"), TTA, NOW.plusDays(1), NOW);
    }

    @Test
    @DisplayName("Scheduled flight changes to another aircraft and revision increases (for the swap to be replicated)")
    void reassignScheduledFlight() {
        ScheduledFlight f = flight();
        f.reassignAircraft(TTB);
        assertEquals(TTB, f.getAircraft());
        assertEquals(2L, f.getRevision());
    }

    @Test
    @DisplayName("Delayed flight can also change aircraft")
    void reassignDelayedFlight() {
        ScheduledFlight f = flight();
        f.changeStatus(FlightStatus.DELAYED, NOW);
        f.reassignAircraft(TTB);
        assertEquals(TTB, f.getAircraft());
    }

    @Test
    @DisplayName("Flight that has already departed cannot change aircraft")
    void cannotReassignAfterDeparture() {
        ScheduledFlight f = flight();
        f.changeStatus(FlightStatus.IN_FLIGHT, NOW);
        assertThrows(SchedulingRuleViolation.class, () -> f.reassignAircraft(TTB));
        assertEquals(TTA, f.getAircraft());
    }

    @Test
    @DisplayName("Changing to the same aircraft has no effect")
    void sameAircraftIsNoOp() {
        ScheduledFlight f = flight();
        f.reassignAircraft(TTA);
        assertEquals(1L, f.getRevision());
    }

    @Test
    @DisplayName("The replicated copy carries the new aircraft to other replicas")
    void replicaCarriesNewAircraft() {
        ScheduledFlight local = ScheduledFlight.restore(FlightId.of("22222222-2222-2222-2222-222222222222"),
                RouteId.of("RT-OPOLIS"), TTA, NOW.plusDays(1), null, null, FlightStatus.SCHEDULED, 1,
                Instant.parse("2026-10-26T12:00:00Z"));
        assertTrue(local.mergeReplica(TTB, FlightStatus.SCHEDULED, null, null, 2, Instant.parse("2026-10-26T12:01:00Z")));
        assertEquals(TTB, local.getAircraft());
        assertEquals(2L, local.getRevision());
    }
}