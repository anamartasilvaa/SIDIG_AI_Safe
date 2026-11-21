package pt.isep.psoft.aisafe.flight_operations.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.PeerClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Anti-entropy repairs flights whose events were lost for this replica (e.g. dead-lettered). */
class ReplicaSyncReconcilerTest {

    private PeerClient peers;
    private FlightReplicaService replicas;
    private ReplicaSyncReconciler sync;

    @BeforeEach
    void setUp() {
        peers = mock(PeerClient.class);
        replicas = mock(FlightReplicaService.class);
        sync = new ReplicaSyncReconciler(peers, replicas);
    }

    private static FlightState state(String id, long revision) {
        Instant t = Instant.parse("2027-03-01T10:00:00Z");
        return new FlightState(id, "RT-OPOLIS", "CS-TTA", LocalDateTime.parse("2027-03-15T10:00:00"),
                null, null, "SCHEDULED", null, revision, t, t, "fo-2");
    }

    @Test
    void missingFlightsAreApplied() {
        when(peers.<FlightState>collectAll(anyString(), any()))
                .thenReturn(new AggregatedResult<>(List.of(state("a", 1), state("b", 2)), List.of()));
        when(replicas.apply(any(), anyString())).thenReturn(true, false);

        assertEquals(1, sync.syncOnce());
        verify(replicas, times(2)).apply(any(), eq("sync"));
    }

    @Test
    void onlyTheNewestRevisionOfEachFlightIsApplied() {
        when(peers.<FlightState>collectAll(anyString(), any()))
                .thenReturn(new AggregatedResult<>(List.of(state("a", 1), state("a", 3)), List.of()));
        when(replicas.apply(any(), anyString())).thenReturn(true);

        assertEquals(1, sync.syncOnce());
        verify(replicas).apply(argThat(s -> s.revision() == 3), eq("sync"));
    }

    @Test
    void ownDatabaseDownStopsTheRoundWithoutError() {
        when(peers.<FlightState>collectAll(anyString(), any()))
                .thenReturn(new AggregatedResult<>(List.of(state("a", 1), state("b", 1)), List.of()));
        when(replicas.apply(any(), anyString())).thenThrow(new RuntimeException("Connection refused"));

        assertEquals(0, sync.syncOnce());
        verify(replicas, times(1)).apply(any(), anyString());
    }

    @Test
    void peersUnreachableIsSkipped() {
        when(peers.<FlightState>collectAll(anyString(), any())).thenThrow(new RuntimeException("down"));
        assertEquals(0, sync.syncOnce());
        verifyNoInteractions(replicas);
    }
}
