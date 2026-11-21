package pt.isep.psoft.aisafe.flight_operations.application.reference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AircraftReferenceServiceTest {

    private static final Instant T1 = Instant.parse("2027-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2027-01-01T11:00:00Z");

    private AircraftRefRepository refs;
    private AircraftReferenceService service;

    @BeforeEach
    void setUp() {
        refs = mock(AircraftRefRepository.class);
        service = new AircraftReferenceService(refs);
    }

    @Test
    void unknownAircraftIsAdded() {
        when(refs.findForUpdate("CS-TTN")).thenReturn(Optional.empty());

        assertTrue(service.apply(new AircraftChange("cs-ttn", "A320", "ACTIVE", 6000.0, 180, 1, T1)));

        verify(refs).save(argThat(a -> a.getRegistrationNumber().equals("CS-TTN")
                && a.getStatus().equals("ACTIVE") && a.getSeatingCapacity() == 180));
    }

    @Test
    void newerStatusIsAppliedAndAircraftBecomesGrounded() {
        AircraftRef ref = AircraftRef.create("CS-TTA", "A320", "ACTIVE", 6000.0, 180, 1, T1);
        when(refs.findForUpdate("CS-TTA")).thenReturn(Optional.of(ref));
        when(refs.findById("CS-TTA")).thenReturn(Optional.of(ref));

        assertTrue(service.apply(new AircraftChange("CS-TTA", null, "UNDER_MAINTENANCE", null, null, 2, T2)));

        assertEquals("UNDER_MAINTENANCE", ref.getStatus());
        assertEquals(180, ref.getSeatingCapacity(), "fields missing from the event keep their value");
        assertTrue(service.isGrounded("CS-TTA"));
    }

    @Test
    void olderOrRepeatedEventIsIgnored() {
        AircraftRef ref = AircraftRef.create("CS-TTA", "A320", "ACTIVE", 6000.0, 180, 5, T2);
        when(refs.findForUpdate("CS-TTA")).thenReturn(Optional.of(ref));

        assertFalse(service.apply(new AircraftChange("CS-TTA", null, "UNDER_MAINTENANCE", null, null, 4, T1)));

        assertEquals("ACTIVE", ref.getStatus());
        verify(refs, never()).save(any());
    }

    @Test
    void unknownAircraftIsNotGrounded() {
        when(refs.findById("CS-XXX")).thenReturn(Optional.empty());
        assertFalse(service.isGrounded("CS-XXX"));
    }
}
