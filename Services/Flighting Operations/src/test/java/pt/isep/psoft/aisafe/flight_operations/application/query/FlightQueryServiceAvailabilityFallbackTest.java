package pt.isep.psoft.aisafe.flight_operations.application.query;

import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AvailabilityDTO;
import pt.isep.psoft.aisafe.flight_operations.application.reference.ReferenceDataService;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightSchedulingPolicy;
import pt.isep.psoft.aisafe.flight_operations.domain.RegistrationNumber;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.FlightViewRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.RouteUtilizationViewRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A replica that has the flight (from the event) but not yet the aircraft reference must answer
 * availability like the others: it learns the aircraft once (fallback lookup) instead of saying "unknown".
 */
class FlightQueryServiceAvailabilityFallbackTest {

    private static final Instant T = Instant.parse("2027-03-01T10:00:00Z");

    @Test
    void unknownAircraftIsLearnedBeforeAnswering() {
        FlightViewRepository flightViews = mock(FlightViewRepository.class);
        AircraftRefRepository aircraftRefs = mock(AircraftRefRepository.class);
        ReferenceDataService references = mock(ReferenceDataService.class);
        FlightQueryService service = new FlightQueryService(flightViews, mock(RouteUtilizationViewRepository.class),
                aircraftRefs,
                new FlightSchedulingPolicy(Duration.ofHours(2)));
        service.setReferenceData(references);

        when(aircraftRefs.findById("CS-TTA"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(AircraftRef.create("CS-TTA", null, "ACTIVE", null, null, 0, T)));
        when(references.ensureAircraft(any(RegistrationNumber.class))).thenReturn(true);
        when(flightViews.findByRegistrationNumberOrderByScheduledDateTimeAsc("CS-TTA")).thenReturn(List.of());

        AvailabilityDTO a = service.availability("CS-TTA", "2027-03-15T13:00:00");
        assertTrue(a.available());
        assertEquals("ACTIVE", a.aircraftStatus());
    }

    @Test
    void aircraftServiceDownKeepsTheUnknownAnswer() {
        FlightViewRepository flightViews = mock(FlightViewRepository.class);
        AircraftRefRepository aircraftRefs = mock(AircraftRefRepository.class);
        ReferenceDataService references = mock(ReferenceDataService.class);
        FlightQueryService service = new FlightQueryService(flightViews, mock(RouteUtilizationViewRepository.class),
                aircraftRefs,
                new FlightSchedulingPolicy(Duration.ofHours(2)));
        service.setReferenceData(references);

        when(aircraftRefs.findById("CS-TTA")).thenReturn(Optional.empty());
        when(references.ensureAircraft(any(RegistrationNumber.class))).thenThrow(new RuntimeException("Aircraft Service down"));
        when(flightViews.findByRegistrationNumberOrderByScheduledDateTimeAsc("CS-TTA")).thenReturn(List.of());

        AvailabilityDTO a = service.availability("CS-TTA", "2027-03-15T13:00:00");
        assertFalse(a.available());
        assertEquals("UNKNOWN", a.aircraftStatus());
    }
}
