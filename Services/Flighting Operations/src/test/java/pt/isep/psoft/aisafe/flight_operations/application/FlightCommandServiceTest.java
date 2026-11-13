package pt.isep.psoft.aisafe.flight_operations.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightEventPublisher;
import pt.isep.psoft.aisafe.flight_operations.application.reference.ReferenceDataService;
import pt.isep.psoft.aisafe.flight_operations.domain.*;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.DependencyUnavailableException;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;
import org.springframework.transaction.support.TransactionOperations;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlightCommandServiceTest {

    private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
    private static final String WHEN = "2027-03-15T10:00:00";

    private ScheduledFlightRepository flights;
    private AircraftRefRepository aircraftRefs;
    private RouteRefRepository routeRefs;
    private ReferenceDataService referenceData;
    private FlightEventPublisher events;
    private FlightCommandService service;

    @BeforeEach
    void setUp() {
        flights = mock(ScheduledFlightRepository.class);
        aircraftRefs = mock(AircraftRefRepository.class);
        routeRefs = mock(RouteRefRepository.class);
        referenceData = mock(ReferenceDataService.class);
        events = mock(FlightEventPublisher.class);
        service = new FlightCommandService(flights, aircraftRefs, routeRefs, referenceData,
                new FlightSchedulingPolicy(Duration.ofHours(2)), events,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC), "fo-1");

        when(referenceData.ensureRoute(any())).thenReturn(true);
        when(referenceData.ensureAircraft(any())).thenReturn(true);
        when(flights.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(flights.findByAircraft_Number(anyString())).thenReturn(List.of());
        givenAircraft("ACTIVE", 6000.0, 180);
        givenRoute(true, 300.0, 100);
    }

    private void givenAircraft(String status, Double range, Integer seats) {
        when(aircraftRefs.findForUpdate("CS-TTA"))
                .thenReturn(Optional.of(AircraftRef.create("CS-TTA", "A320", status, range, seats, 1, NOW)));
    }

    private void givenRoute(boolean active, Double minimumRange, Integer minimumCapacity) {
        when(routeRefs.findById("RT-OPOLIS"))
                .thenReturn(Optional.of(RouteRef.create("RT-OPOLIS", "OPO", "LIS", active, null,
                        minimumRange, minimumCapacity, 1, NOW)));
    }

    private ScheduledFlight schedule() {
        return service.schedule(new CreateScheduledFlightDTO("RT-OPOLIS", "CS-TTA", WHEN), "corr-1");
    }

    @Test
    void validFlightIsSavedAndItsEventIsWritten() {
        ScheduledFlight f = schedule();
        assertEquals("fo-1", f.getOriginInstance());
        assertEquals(1, f.getRevision());
        verify(flights).save(f);
        verify(events).flightScheduled(f, "corr-1");
    }

    @Test
    void inactiveRouteIsRejectedWithoutEvent() {
        givenRoute(false, 300.0, 100);
        assertThrows(SchedulingRuleViolation.class, this::schedule);
        verify(flights, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void aircraftUnderMaintenanceIsRejected() {
        givenAircraft("UNDER_MAINTENANCE", 6000.0, 180);
        assertThrows(SchedulingRuleViolation.class, this::schedule);
        verifyNoInteractions(events);
    }

    @Test
    void insufficientCapacityIsRejected() {
        givenAircraft("ACTIVE", 6000.0, 50);
        assertThrows(SchedulingRuleViolation.class, this::schedule);
    }

    @Test
    void conflictWithinTwoHoursIsRejected() {
        ScheduledFlight existing = new ScheduledFlight(RouteId.of("RT-X"), RegistrationNumber.of("CS-TTA"),
                LocalDateTime.parse("2027-03-15T11:00:00"), LocalDateTime.parse("2027-03-01T10:00:00"), "fo-2");
        when(flights.findByAircraft_Number("CS-TTA")).thenReturn(List.of(existing));
        SchedulingRuleViolation e = assertThrows(SchedulingRuleViolation.class, this::schedule);
        assertTrue(e.getMessage().contains("already has flight"));
        verifyNoInteractions(events);
    }

    @Test
    void unknownAircraftIs409AndUnknownWithServiceDownIs503() {
        when(referenceData.ensureAircraft(any())).thenReturn(false);
        assertThrows(SchedulingRuleViolation.class, this::schedule);

        when(referenceData.ensureAircraft(any())).thenThrow(new DependencyUnavailableException("aircraft down"));
        assertThrows(DependencyUnavailableException.class, this::schedule);
        verifyNoInteractions(events);
    }

    @Test
    void pastDateIs400() {
        assertThrows(IllegalArgumentException.class, () ->
                service.schedule(new CreateScheduledFlightDTO("RT-OPOLIS", "CS-TTA", "2020-01-01T10:00:00"), null));
    }

    @Test
    void statusChangeWritesEventWithPreviousStatus() {
        ScheduledFlight f = new ScheduledFlight(RouteId.of("RT-OPOLIS"), RegistrationNumber.of("CS-TTA"),
                LocalDateTime.parse(WHEN), LocalDateTime.parse("2027-03-01T10:00:00"), "fo-1");
        when(flights.findByFlightId_Uuid(f.getFlightId().value())).thenReturn(Optional.of(f));

        Optional<ScheduledFlight> result = service.changeStatus(f.getFlightId().value(), "DELAYED", "corr-2");

        assertEquals(FlightStatus.DELAYED, result.orElseThrow().getStatus());
        assertEquals(2, f.getRevision());
        verify(events).flightStatusChanged(f, "SCHEDULED", "corr-2");
    }

    @Test
    void sameStatusDoesNotWriteAnEvent() {
        ScheduledFlight f = new ScheduledFlight(RouteId.of("RT-OPOLIS"), RegistrationNumber.of("CS-TTA"),
                LocalDateTime.parse(WHEN), LocalDateTime.parse("2027-03-01T10:00:00"), "fo-1");
        when(flights.findByFlightId_Uuid(f.getFlightId().value())).thenReturn(Optional.of(f));
        service.changeStatus(f.getFlightId().value(), "SCHEDULED", null);
        verifyNoInteractions(events);
    }

    @Test
    void unknownFlightIsEmpty() {
        when(flights.findByFlightId_Uuid(anyString())).thenReturn(Optional.empty());
        assertTrue(service.changeStatus("11111111-1111-1111-1111-111111111111", "DELAYED", null).isEmpty());
    }
}