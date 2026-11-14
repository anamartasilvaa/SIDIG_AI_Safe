package pt.isep.psoft.aisafe.flight_operations.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightEventPublisher;
import pt.isep.psoft.aisafe.flight_operations.application.query.FlightProjector;
import pt.isep.psoft.aisafe.flight_operations.domain.*;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundingServiceTest {

    private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
    private static final LocalDateTime CREATED = LocalDateTime.parse("2027-03-01T09:00:00");

    private final List<ScheduledFlight> store = new ArrayList<>();
    private AircraftRefRepository aircraftRefs;
    private FlightEventPublisher events;
    private GroundingService service;

    @BeforeEach
    void setUp() {
        ScheduledFlightRepository flights = mock(ScheduledFlightRepository.class);
        aircraftRefs = mock(AircraftRefRepository.class);
        RouteRefRepository routeRefs = mock(RouteRefRepository.class);
        events = mock(FlightEventPublisher.class);
        service = new GroundingService(flights, aircraftRefs, routeRefs, new FlightSchedulingPolicy(Duration.ofHours(2)),
                events, mock(FlightProjector.class), Clock.fixed(NOW, ZoneOffset.UTC));

        when(flights.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(flights.findByAircraft_Number(anyString())).thenAnswer(inv -> store.stream()
                .filter(f -> f.getAircraft().value().equals(inv.getArgument(0))).toList());
        when(routeRefs.findById("RT-OPOLIS")).thenReturn(Optional.of(
                RouteRef.create("RT-OPOLIS", "OPO", "LIS", true, null, 1000.0, 150, 1, NOW)));
    }

    private ScheduledFlight flight(String aircraft, String when) {
        ScheduledFlight f = new ScheduledFlight(RouteId.of("RT-OPOLIS"), RegistrationNumber.of(aircraft),
                LocalDateTime.parse(when), CREATED, "fo-1");
        store.add(f);
        return f;
    }

    private void candidates(AircraftRef... refs) {
        when(aircraftRefs.findByStatusOrderByRegistrationNumberAsc("ACTIVE")).thenReturn(List.of(refs));
    }

    private static AircraftRef ref(String reg, double range, int seats) {
        return AircraftRef.create(reg, "A320", "ACTIVE", range, seats, 1, NOW);
    }

    @Test
    void flightMovesToFirstCompatibleFreeAircraft() {
        ScheduledFlight f = flight("CS-TTM", "2027-03-15T10:00:00");
        flight("CS-TTA", "2027-03-15T11:00:00");                 // CS-TTA busy at that time
        candidates(ref("CS-TTA", 6000, 180), ref("CS-TTB", 6000, 120), ref("CS-TTC", 6000, 180));

        List<String> report = service.ground("CS-TTM", "c");

        assertEquals("CS-TTC", f.getAircraft().value(), "TTA busy, TTB too small (120 < 150)");
        assertTrue(report.get(0).contains("reassigned from CS-TTM to CS-TTC"));
        verify(events).flightReassigned(f, "CS-TTM", "c");
    }

    @Test
    void noCandidateMeansDelayedWithReason() {
        ScheduledFlight f = flight("CS-TTM", "2027-03-15T10:00:00");
        candidates(ref("CS-TTB", 6000, 120));

        service.ground("CS-TTM", "c");

        assertEquals("CS-TTM", f.getAircraft().value());
        assertEquals(FlightStatus.DELAYED, f.getStatus());
        assertEquals(GroundingService.NO_REPLACEMENT, f.getStatusReason());
        verify(events).flightStatusChanged(f, "SCHEDULED", "c");
    }

    @Test
    void pastAndFinishedFlightsAreNotTouched() {
        ScheduledFlight past = ScheduledFlight.restore(FlightId.newId(), RouteId.of("RT-OPOLIS"),
                RegistrationNumber.of("CS-TTM"), LocalDateTime.parse("2027-02-01T10:00:00"), null, null,
                FlightStatus.SCHEDULED, 1, NOW);
        store.add(past);
        ScheduledFlight cancelled = flight("CS-TTM", "2027-03-20T10:00:00");
        cancelled.changeStatus(FlightStatus.CANCELED, CREATED);
        candidates(ref("CS-TTA", 6000, 180));

        List<String> report = service.ground("CS-TTM", "c");

        assertEquals("CS-TTM", past.getAircraft().value());
        assertEquals("CS-TTM", cancelled.getAircraft().value());
        assertTrue(report.get(0).contains("no upcoming flights"));
        verifyNoInteractions(events);
    }

    @Test
    void secondRunIsIdempotent() {
        flight("CS-TTM", "2027-03-15T10:00:00");
        candidates(ref("CS-TTA", 6000, 180));
        service.ground("CS-TTM", "c");
        clearInvocations(events);

        service.ground("CS-TTM", "c");
        verifyNoInteractions(events);
    }
}
