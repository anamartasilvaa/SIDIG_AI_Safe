package pt.isep.psoft.aisafe.flight_operations.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightEventPublisher;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.application.query.FlightProjector;
import pt.isep.psoft.aisafe.flight_operations.domain.*;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlightReplicaServiceTest {

    private static final Instant T1 = Instant.parse("2027-03-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2027-03-01T11:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.parse("2027-03-01T12:00:00");

    private ScheduledFlightRepository flights;
    private FlightProjector projector;
    private FlightEventPublisher events;
    private FlightReplicaService service;
    private final List<ScheduledFlight> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        flights = mock(ScheduledFlightRepository.class);
        projector = mock(FlightProjector.class);
        events = mock(FlightEventPublisher.class);
        AircraftRefRepository aircraftRefs = mock(AircraftRefRepository.class);
        ConflictResolver conflicts = new ConflictResolver(flights, new FlightSchedulingPolicy(Duration.ofHours(2)),
                events, projector, Clock.fixed(Instant.parse("2027-03-01T12:00:00Z"), ZoneOffset.UTC));
        service = new FlightReplicaService(flights, aircraftRefs, projector, conflicts);

        when(flights.save(any())).thenAnswer(inv -> {
            ScheduledFlight f = inv.getArgument(0);
            if (!store.contains(f)) store.add(f);
            return f;
        });
        when(flights.findByFlightId_Uuid(anyString())).thenAnswer(inv -> store.stream()
                .filter(f -> f.getFlightId().value().equals(inv.getArgument(0))).findFirst());
        when(flights.findByAircraft_Number(anyString())).thenAnswer(inv -> store.stream()
                .filter(f -> f.getAircraft().value().equals(inv.getArgument(0))).toList());
    }

    private static FlightState state(String id, String time, long rev, String status, Instant modified,
                                     Instant created) {
        return new FlightState(id, "RT-OPOLIS", "CS-TTA", LocalDateTime.parse(time), null, null,
                status, null, rev, modified, created, "fo-2");
    }

    private static String id(int n) {
        return "00000000-0000-0000-0000-00000000000" + n;
    }

    @Test
    void unknownFlightIsAddedAndProjected() {
        FlightState s = state(id(1), "2027-03-15T10:00:00", 1, "SCHEDULED", T1, T1);
        assertTrue(service.apply(s, "c"));
        assertEquals(1, store.size());
        assertEquals("fo-2", store.get(0).getOriginInstance());
        verify(projector).project(s);
        verifyNoInteractions(events);
    }

    @Test
    void olderEventDoesNotOverwriteButViewIsStillAskedToProject() {
        service.apply(state(id(1), "2027-03-15T10:00:00", 3, "DELAYED", T2, T1), "c");
        assertFalse(service.apply(state(id(1), "2027-03-15T10:00:00", 2, "SCHEDULED", T1, T1), "c"));
        assertEquals(FlightStatus.DELAYED, store.get(0).getStatus());
    }

    @Test
    void conflictCancelsTheYoungestFlightOnly() {
        // X created first (wins), Y created later on another replica, 1 h apart on the same aircraft.
        service.apply(state(id(1), "2027-03-15T10:00:00", 1, "SCHEDULED", T1, T1), "c");
        service.apply(state(id(2), "2027-03-15T11:00:00", 1, "SCHEDULED", T2, T2), "c");

        ScheduledFlight x = store.get(0);
        ScheduledFlight y = store.get(1);
        assertEquals(FlightStatus.SCHEDULED, x.getStatus());
        assertEquals(FlightStatus.CANCELED, y.getStatus());
        assertTrue(y.getStatusReason().startsWith(ConflictResolver.REASON_PREFIX));
        assertEquals(2, y.getRevision());
        verify(events).flightStatusChanged(y, "SCHEDULED", "c");
    }

    @Test
    void sameWinnerWhateverTheArrivalOrder() {
        // Y arrives first this time: the result must be the same (X kept, Y cancelled).
        service.apply(state(id(2), "2027-03-15T11:00:00", 1, "SCHEDULED", T2, T2), "c");
        service.apply(state(id(1), "2027-03-15T10:00:00", 1, "SCHEDULED", T1, T1), "c");

        ScheduledFlight y = store.get(0);
        ScheduledFlight x = store.get(1);
        assertEquals(FlightStatus.CANCELED, y.getStatus());
        assertEquals(FlightStatus.SCHEDULED, x.getStatus());
    }

    @Test
    void flightsMoreThanTwoHoursApartDoNotConflict() {
        service.apply(state(id(1), "2027-03-15T10:00:00", 1, "SCHEDULED", T1, T1), "c");
        service.apply(state(id(2), "2027-03-15T12:00:00", 1, "SCHEDULED", T2, T2), "c");
        assertTrue(store.stream().allMatch(f -> f.getStatus() == FlightStatus.SCHEDULED));
        verifyNoInteractions(events);
    }

    @Test
    void cancelledFlightDoesNotConflict() {
        service.apply(state(id(1), "2027-03-15T10:00:00", 2, "CANCELED", T1, T1), "c");
        service.apply(state(id(2), "2027-03-15T10:30:00", 1, "SCHEDULED", T2, T2), "c");
        assertEquals(FlightStatus.SCHEDULED, store.get(1).getStatus());
        verifyNoInteractions(events);
    }
}
