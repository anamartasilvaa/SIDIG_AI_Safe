package pt.isep.psoft.aisafe.flight_operations.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ReplicaMergeTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final Instant T1 = Instant.parse("2026-10-14T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-14T12:05:00Z");

    private ScheduledFlight copy(FlightStatus status, long revision, Instant at) {
        return ScheduledFlight.restore(FlightId.of("11111111-1111-1111-1111-111111111111"), RouteId.of("OPO-LIS"),
                RegistrationNumber.of("CS-TTA"), NOW.plusDays(1), null, null, status, revision, at);
    }

    @Test
    @DisplayName("Voo novo comeca na revision 1; cada mudanca de estado incrementa; pedido repetido nao")
    void revisionCountsChanges() {
        ScheduledFlight flight = new ScheduledFlight(RouteId.of("OPO-LIS"), RegistrationNumber.of("CS-TTA"),
                NOW.plusDays(1), NOW);
        assertEquals(1L, flight.getRevision());
        flight.changeStatus(FlightStatus.DELAYED, NOW);
        assertEquals(2L, flight.getRevision());
        flight.changeStatus(FlightStatus.DELAYED, NOW);      // idempotente
        assertEquals(2L, flight.getRevision());
    }

    @Test
    @DisplayName("Copia mais recente (revision maior) e aplicada")
    void newerReplicaWins() {
        ScheduledFlight local = copy(FlightStatus.SCHEDULED, 1, T1);
        assertTrue(local.mergeReplica(FlightStatus.DELAYED, null, null, 2, T2));
        assertEquals(FlightStatus.DELAYED, local.getStatus());
        assertEquals(2L, local.getRevision());
    }

    @Test
    @DisplayName("Copia antiga ou repetida e ignorada (chegar fora de ordem nao estraga nada)")
    void olderOrDuplicateReplicaIgnored() {
        ScheduledFlight local = copy(FlightStatus.DELAYED, 2, T2);
        assertFalse(local.mergeReplica(FlightStatus.SCHEDULED, null, null, 1, T1));
        assertFalse(local.mergeReplica(FlightStatus.DELAYED, null, null, 2, T2));
        assertEquals(FlightStatus.DELAYED, local.getStatus());
    }

    @Test
    @DisplayName("Alteracoes concorrentes com a mesma revision: ganha a mais recente, em todas as replicas")
    void concurrentChangesConverge() {
        ScheduledFlight a = copy(FlightStatus.DELAYED, 2, T1);
        ScheduledFlight b = copy(FlightStatus.CANCELED, 2, T2);

        a.mergeReplica(b.getStatus(), null, null, b.getRevision(), b.getLastModifiedAt());
        b.mergeReplica(FlightStatus.DELAYED, null, null, 2, T1);

        assertEquals(FlightStatus.CANCELED, a.getStatus());
        assertEquals(FlightStatus.CANCELED, b.getStatus());
    }
}
