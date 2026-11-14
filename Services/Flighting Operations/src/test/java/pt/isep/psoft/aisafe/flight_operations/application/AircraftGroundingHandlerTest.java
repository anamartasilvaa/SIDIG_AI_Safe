package pt.isep.psoft.aisafe.flight_operations.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.reference.AircraftChange;
import pt.isep.psoft.aisafe.flight_operations.application.reference.AircraftReferenceService;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AircraftGroundingHandlerTest {

    private static final Instant T = Instant.parse("2027-01-01T10:00:00Z");

    private AircraftReferenceService references;
    private GroundingService grounding;
    private AircraftGroundingHandler handler;

    @BeforeEach
    void setUp() {
        references = mock(AircraftReferenceService.class);
        grounding = mock(GroundingService.class);
        handler = new AircraftGroundingHandler(references, grounding);
        when(grounding.ground(anyString(), anyString())).thenReturn(List.of("report"));
    }

    private static AircraftChange status(String status) {
        return new AircraftChange("CS-TTM", null, status, null, null, 3, T);
    }

    @Test
    void aircraftNoLongerActiveIsGrounded() {
        when(references.isGrounded("CS-TTM")).thenReturn(true);

        assertEquals(List.of("report"), handler.onAircraftStatus(status("UNDER_MAINTENANCE"), "c"));

        verify(references).apply(any());
        verify(grounding).ground("CS-TTM", "c");
    }

    @Test
    void activeAircraftIsNotGrounded() {
        when(references.isGrounded("CS-TTM")).thenReturn(false);

        assertTrue(handler.onAircraftStatus(status("ACTIVE"), "c").isEmpty());

        verify(grounding, never()).ground(anyString(), anyString());
    }

    @Test
    void oldEventDoesNotGroundAnAircraftThatIsActiveAgain() {
        // the local copy already has a newer ACTIVE status, so the old event changes nothing
        when(references.apply(any())).thenReturn(false);
        when(references.isGrounded("CS-TTM")).thenReturn(false);

        handler.onAircraftStatus(status("UNDER_MAINTENANCE"), "c");

        verify(grounding, never()).ground(anyString(), anyString());
    }

    @Test
    void eventWithoutStatusIsIgnored() {
        assertTrue(handler.onAircraftStatus(status(null), "c").isEmpty());
        verifyNoInteractions(grounding);
        verify(references, never()).apply(any());
    }

    @Test
    void groundingRequestedAlwaysGrounds() {
        handler.onGroundingRequested("CS-TTM", "c");
        verify(grounding).ground("CS-TTM", "c");
    }
}
