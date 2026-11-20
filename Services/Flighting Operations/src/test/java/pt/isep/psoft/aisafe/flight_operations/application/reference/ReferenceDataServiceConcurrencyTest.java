package pt.isep.psoft.aisafe.flight_operations.application.reference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import pt.isep.psoft.aisafe.flight_operations.domain.AircraftCapabilities;
import pt.isep.psoft.aisafe.flight_operations.domain.RegistrationNumber;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteId;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteRequirements;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Two requests that learn the same aircraft/route at the same time must not fail (no 500). */
class ReferenceDataServiceConcurrencyTest {

    private AircraftRefRepository aircraft;
    private RouteRefRepository routes;
    private ReferenceLookup lookup;
    private ReferenceWriter writer;
    private ReferenceDataService service;

    @BeforeEach
    void setUp() {
        aircraft = mock(AircraftRefRepository.class);
        routes = mock(RouteRefRepository.class);
        lookup = mock(ReferenceLookup.class);
        writer = mock(ReferenceWriter.class);
        service = new ReferenceDataService(routes, aircraft, lookup, writer,
                Clock.fixed(Instant.parse("2027-03-01T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void aircraftInsertedConcurrentlyIsStillKnown() {
        RegistrationNumber reg = RegistrationNumber.of("CS-TTA");
        when(aircraft.existsById("CS-TTA")).thenReturn(false);
        when(lookup.aircraft(reg)).thenReturn(Optional.of(new AircraftCapabilities("CS-TTA", "ACTIVE", 5000.0, 180)));
        doThrow(new DataIntegrityViolationException("duplicate key")).when(writer).insertAircraft(any(AircraftRef.class));

        assertTrue(service.ensureAircraft(reg));
    }

    @Test
    void routeInsertedConcurrentlyIsStillKnown() {
        RouteId id = RouteId.of("RT-OPOLIS");
        when(routes.existsById("RT-OPOLIS")).thenReturn(false);
        when(lookup.route(id)).thenReturn(Optional.of(new RouteRequirements("RT-OPOLIS", true, 300.0, 300.0, 50)));
        doThrow(new DataIntegrityViolationException("duplicate key")).when(writer).insertRoute(any(RouteRef.class));

        assertTrue(service.ensureRoute(id));
    }

    @Test
    void knownAircraftIsNotLookedUpAgain() {
        when(aircraft.existsById("CS-TTA")).thenReturn(true);
        assertTrue(service.ensureAircraft(RegistrationNumber.of("CS-TTA")));
        verifyNoInteractions(lookup, writer);
    }

    @Test
    void unknownEverywhereIsFalse() {
        RegistrationNumber reg = RegistrationNumber.of("CS-NOPE");
        when(aircraft.existsById("CS-NOPE")).thenReturn(false);
        when(lookup.aircraft(reg)).thenReturn(Optional.empty());
        assertFalse(service.ensureAircraft(reg));
        verifyNoInteractions(writer);
    }
}
