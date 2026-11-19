package pt.isep.psoft.aisafe.flight_operations.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightEventPublisher;
import pt.isep.psoft.aisafe.flight_operations.domain.*;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundingReconcilerTest {

    private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
    private static final LocalDateTime CREATED = LocalDateTime.parse("2027-03-01T09:00:00");

    private final List<ScheduledFlight> store = new ArrayList<>();
    private AircraftRefRepository refs;
    private FlightEventPublisher events;
    private GroundingReconciler reconciler;

    @BeforeEach
    void setUp() {
        refs = mock(AircraftRefRepository.class);
        ScheduledFlightRepository flights = mock(ScheduledFlightRepository.class);
        events = mock(FlightEventPublisher.class);
        reconciler = new GroundingReconciler(refs, flights, events, TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(flights.findByAircraft_Number(anyString())).thenAnswer(inv -> store.stream()
                .filter(f -> f.getAircraft().value().equals(inv.getArgument(0))).toList());
    }

    private ScheduledFlight flight(String reg, String when) {
        // created long before, so that a flight that is now in the past was valid when scheduled
        ScheduledFlight f = new ScheduledFlight(RouteId.of("RT-OPOLIS"), RegistrationNumber.of(reg),
                LocalDateTime.parse(when), LocalDateTime.parse("2026-01-01T00:00:00"), "fo-1");
        store.add(f);
        return f;
    }

    private void aircraft(String reg, String status) {
        when(refs.findAll()).thenReturn(List.of(AircraftRef.create(reg, "A320", status, 6000.0, 180, 1, NOW)));
    }

    @Test
    void groundedAircraftWithFutureFlightIsRequestedOnce() {
        aircraft("CS-TTM", "UNDER_MAINTENANCE");
        flight("CS-TTM", "2027-03-15T10:00:00");

        reconciler.reconcile();
        reconciler.reconcile();   // within the cool-down: not requested again

        verify(events, times(1)).groundingRequested(eq("CS-TTM"), anyString());
    }

    @Test
    void activeAircraftIsIgnored() {
        aircraft("CS-TTA", "ACTIVE");
        flight("CS-TTA", "2027-03-15T10:00:00");

        reconciler.reconcile();

        verifyNoInteractions(events);
    }

    @Test
    void flightsAlreadyHandledOrInThePastAreIgnored() {
        aircraft("CS-TTM", "UNDER_MAINTENANCE");
        flight("CS-TTM", "2027-02-01T10:00:00");                         // past
        flight("CS-TTM", "2027-03-15T10:00:00")
                .changeStatus(FlightStatus.DELAYED, CREATED, GroundingService.NO_REPLACEMENT); // already handled

        reconciler.reconcile();

        verifyNoInteractions(events);
    }
}
