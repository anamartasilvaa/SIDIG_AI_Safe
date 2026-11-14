package pt.isep.psoft.aisafe.flight_operations.application.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AvailabilityDTO;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightSchedulingPolicy;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.AircraftMoodViewRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.FlightViewRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.RouteUtilizationViewRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlightQueryServiceTest {

    private static final Instant T = Instant.parse("2027-03-01T10:00:00Z");

    private FlightViewRepository flightViews;
    private AircraftRefRepository aircraftRefs;
    private FlightQueryService service;

    @BeforeEach
    void setUp() {
        flightViews = mock(FlightViewRepository.class);
        aircraftRefs = mock(AircraftRefRepository.class);
        service = new FlightQueryService(flightViews, mock(RouteUtilizationViewRepository.class), aircraftRefs,
                mock(AircraftMoodViewRepository.class), new FlightSchedulingPolicy(Duration.ofHours(2)));
    }

    private static FlightView view(String status) {
        return FlightView.from(new FlightState("11111111-1111-1111-1111-111111111111", "RT-OPOLIS", "CS-TTA",
                LocalDateTime.parse("2027-03-15T10:00:00"), null, null, status, null, 1, T, T, "fo-1"), "OPO", "LIS");
    }

    @Test
    void availableWhenActiveAndNoConflict() {
        when(aircraftRefs.findById("CS-TTA")).thenReturn(Optional.of(AircraftRef.create("CS-TTA", null, "ACTIVE", null, null, 1, T)));
        when(flightViews.findByRegistrationNumberOrderByScheduledDateTimeAsc("CS-TTA")).thenReturn(List.of(view("SCHEDULED")));

        AvailabilityDTO a = service.availability("CS-TTA", "2027-03-15T13:00:00");
        assertTrue(a.available());
        assertEquals("ACTIVE", a.aircraftStatus());
    }

    @Test
    void notAvailableWithConflictOrMaintenanceOrUnknown() {
        when(flightViews.findByRegistrationNumberOrderByScheduledDateTimeAsc("CS-TTA")).thenReturn(List.of(view("SCHEDULED")));
        when(aircraftRefs.findById("CS-TTA")).thenReturn(Optional.of(AircraftRef.create("CS-TTA", null, "ACTIVE", null, null, 1, T)));
        AvailabilityDTO conflict = service.availability("CS-TTA", "2027-03-15T11:00:00");
        assertFalse(conflict.available());
        assertNotNull(conflict.conflictingFlightId());

        when(aircraftRefs.findById("CS-TTA")).thenReturn(Optional.of(AircraftRef.create("CS-TTA", null, "UNDER_MAINTENANCE", null, null, 1, T)));
        assertFalse(service.availability("CS-TTA", "2027-03-20T10:00:00").available());

        when(aircraftRefs.findById("CS-TTA")).thenReturn(Optional.empty());
        AvailabilityDTO unknown = service.availability("CS-TTA", "2027-03-20T10:00:00");
        assertFalse(unknown.available());
        assertEquals("UNKNOWN", unknown.aircraftStatus());
    }

    @Test
    void cancelledFlightsDoNotBlock() {
        when(aircraftRefs.findById("CS-TTA")).thenReturn(Optional.of(AircraftRef.create("CS-TTA", null, "ACTIVE", null, null, 1, T)));
        when(flightViews.findByRegistrationNumberOrderByScheduledDateTimeAsc("CS-TTA")).thenReturn(List.of(view("CANCELED")));
        assertTrue(service.availability("CS-TTA", "2027-03-15T10:30:00").available());
    }

    @Test
    void unknownMoodFilterIs400() {
        assertThrows(IllegalArgumentException.class, () -> service.unicorns("sad"));
    }

    @Test
    void invalidIdIs400() {
        assertThrows(IllegalArgumentException.class, () -> service.findById("not-a-uuid"));
    }
}
