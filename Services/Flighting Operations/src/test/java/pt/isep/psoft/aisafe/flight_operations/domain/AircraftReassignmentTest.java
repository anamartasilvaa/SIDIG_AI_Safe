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
    @DisplayName("Voo agendado passa para outro aviao e a revision aumenta (para a troca ser replicada)")
    void reassignScheduledFlight() {
        ScheduledFlight f = flight();
        f.reassignAircraft(TTB);
        assertEquals(TTB, f.getAircraft());
        assertEquals(2L, f.getRevision());
    }

    @Test
    @DisplayName("Voo atrasado tambem pode trocar de aviao")
    void reassignDelayedFlight() {
        ScheduledFlight f = flight();
        f.changeStatus(FlightStatus.DELAYED, NOW);
        f.reassignAircraft(TTB);
        assertEquals(TTB, f.getAircraft());
    }

    @Test
    @DisplayName("Voo que ja partiu nao pode trocar de aviao")
    void cannotReassignAfterDeparture() {
        ScheduledFlight f = flight();
        f.changeStatus(FlightStatus.IN_FLIGHT, NOW);
        assertThrows(SchedulingRuleViolation.class, () -> f.reassignAircraft(TTB));
        assertEquals(TTA, f.getAircraft());
    }

    @Test
    @DisplayName("Trocar para o mesmo aviao nao tem efeito")
    void sameAircraftIsNoOp() {
        ScheduledFlight f = flight();
        f.reassignAircraft(TTA);
        assertEquals(1L, f.getRevision());
    }

    @Test
    @DisplayName("A copia replicada leva o novo aviao para as outras replicas")
    void replicaCarriesNewAircraft() {
        ScheduledFlight local = ScheduledFlight.restore(FlightId.of("22222222-2222-2222-2222-222222222222"),
                RouteId.of("RT-OPOLIS"), TTA, NOW.plusDays(1), null, null, FlightStatus.SCHEDULED, 1,
                Instant.parse("2026-10-26T12:00:00Z"));
        assertTrue(local.mergeReplica(TTB, FlightStatus.SCHEDULED, null, null, 2, Instant.parse("2026-10-26T12:01:00Z")));
        assertEquals(TTB, local.getAircraft());
        assertEquals(2L, local.getRevision());
    }
}
