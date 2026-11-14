package pt.isep.psoft.aisafe.flight_operations.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.PeerClient;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SnapshotBootstrapperTest {

    private ScheduledFlightRepository flights;
    private PeerClient peers;
    private FlightReplicaService replicas;
    private SnapshotBootstrapper bootstrapper;

    @BeforeEach
    void setUp() {
        flights = mock(ScheduledFlightRepository.class);
        peers = mock(PeerClient.class);
        replicas = mock(FlightReplicaService.class);
        bootstrapper = new SnapshotBootstrapper(flights, peers, replicas, Duration.ofMillis(1), 3);
        when(replicas.apply(any(), anyString())).thenReturn(true);
    }

    private static FlightState state(String id, long revision, String created) {
        return new FlightState(id, "RT-OPOLIS", "CS-TTA", LocalDateTime.parse("2027-03-15T10:00:00"),
                null, null, "SCHEDULED", null, revision, Instant.parse(created), Instant.parse(created), "fo-2");
    }

    private void peersAnswer(List<FlightState> items, List<String> unreachable) {
        when(peers.<FlightState>collectAll(anyString(), any())).thenReturn(new AggregatedResult<>(items, unreachable));
    }

    @Test
    void replicaWithDataDoesNotAskForSnapshot() {
        when(flights.count()).thenReturn(3L);

        bootstrapper.onApplicationReady();

        verifyNoInteractions(peers);
    }

    @Test
    void noPeerAnsweredMeansTryAgain() {
        peersAnswer(List.of(), List.of("https://flight-operations-1:8443"));

        assertFalse(bootstrapper.bootstrapOnce());

        bootstrapper.run();
        verify(peers, times(4)).collectAll(anyString(), any());   // 1 + 3 attempts
        verifyNoInteractions(replicas);
    }

    @Test
    void peerWithNoFlightsEndsTheBootstrap() {
        peersAnswer(List.of(), List.of());

        assertTrue(bootstrapper.bootstrapOnce());
        verifyNoInteractions(replicas);
    }

    @Test
    void flightsAreAppliedOldestFirstWithHighestRevision() {
        FlightState older = state("b", 1, "2027-01-01T08:00:00Z");
        FlightState newerRev1 = state("a", 1, "2027-01-01T09:00:00Z");
        FlightState newerRev3 = state("a", 3, "2027-01-01T09:00:00Z");
        peersAnswer(List.of(newerRev1, older, newerRev3), List.of());

        assertTrue(bootstrapper.bootstrapOnce());

        InOrder order = inOrder(replicas);
        order.verify(replicas).apply(older, "bootstrap");
        order.verify(replicas).apply(newerRev3, "bootstrap");
        verify(replicas, never()).apply(newerRev1, "bootstrap");
    }

    @Test
    void oneFailingFlightDoesNotStopTheOthers() {
        FlightState bad = state("a", 1, "2027-01-01T08:00:00Z");
        FlightState good = state("b", 1, "2027-01-01T09:00:00Z");
        peersAnswer(List.of(bad, good), List.of());
        when(replicas.apply(eq(bad), anyString())).thenThrow(new IllegalStateException("boom"));

        assertTrue(bootstrapper.bootstrapOnce());

        verify(replicas).apply(good, "bootstrap");
    }
}
