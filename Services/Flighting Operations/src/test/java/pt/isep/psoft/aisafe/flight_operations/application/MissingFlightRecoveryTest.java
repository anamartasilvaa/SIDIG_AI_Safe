package pt.isep.psoft.aisafe.flight_operations.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.DependencyUnavailableException;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.PeerClient;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissingFlightRecoveryTest {

    private static final String ID = "11111111-1111-1111-1111-111111111111";

    private ScheduledFlightRepository flights;
    private PeerClient peers;
    private FlightReplicaService replicas;
    private MissingFlightRecovery recovery;

    @BeforeEach
    void setUp() {
        flights = mock(ScheduledFlightRepository.class);
        peers = mock(PeerClient.class);
        replicas = mock(FlightReplicaService.class);
        recovery = new MissingFlightRecovery(flights, peers, replicas);
    }

    @Test
    void flightAlreadyHereNeedsNoPeer() {
        when(flights.findByFlightId_Uuid(ID)).thenReturn(Optional.of(mock(ScheduledFlight.class)));

        assertFalse(recovery.fetchIfMissing(ID));

        verifyNoInteractions(peers, replicas);
    }

    @Test
    void missingFlightIsCopiedFromAPeer() {
        FlightState s = new FlightState(ID, "RT-OPOLIS", "CS-TTA", LocalDateTime.parse("2027-03-15T10:00:00"),
                null, null, "SCHEDULED", null, 1, Instant.parse("2027-03-01T10:00:00Z"),
                Instant.parse("2027-03-01T10:00:00Z"), "fo-2");
        when(flights.findByFlightId_Uuid(ID)).thenReturn(Optional.empty());
        when(peers.findFirst("/internal/scheduled-flights/" + ID, FlightState.class)).thenReturn(Optional.of(s));

        assertTrue(recovery.fetchIfMissing(ID));

        verify(replicas).apply(s, "peer-fetch");
    }

    @Test
    void peersDownMeansNoChange() {
        when(flights.findByFlightId_Uuid(ID)).thenReturn(Optional.empty());
        when(peers.findFirst(anyString(), eq(FlightState.class)))
                .thenThrow(new DependencyUnavailableException("peers down"));

        assertFalse(recovery.fetchIfMissing(ID));

        verifyNoInteractions(replicas);
    }
}
