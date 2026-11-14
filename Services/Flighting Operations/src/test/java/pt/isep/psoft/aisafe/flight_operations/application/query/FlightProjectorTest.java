package pt.isep.psoft.aisafe.flight_operations.application.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.FlightViewRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.RouteUtilizationViewRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FlightProjectorTest {

    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final Instant T1 = Instant.parse("2027-03-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2027-03-01T11:00:00Z");

    private FlightViewRepository flightViews;
    private RouteUtilizationViewRepository routeUtilization;
    private RouteRefRepository routeRefs;
    private FlightProjector projector;

    @BeforeEach
    void setUp() {
        flightViews = mock(FlightViewRepository.class);
        routeUtilization = mock(RouteUtilizationViewRepository.class);
        routeRefs = mock(RouteRefRepository.class);
        projector = new FlightProjector(flightViews, routeUtilization, routeRefs);
        when(routeRefs.findById("RT-OPOLIS")).thenReturn(Optional.of(
                RouteRef.create("RT-OPOLIS", "OPO", "LIS", true, null, 300.0, 100, 1, T1)));
        when(routeUtilization.findById(any())).thenReturn(Optional.empty());
    }

    private static FlightState state(long revision, String status, Instant modified) {
        return new FlightState(ID, "RT-OPOLIS", "CS-TTA", LocalDateTime.parse("2027-03-15T10:00:00"),
                null, null, status, null, revision, modified, T1, "fo-1");
    }

    @Test
    void newFlightCreatesTheViewWithOriginAndDestination() {
        when(flightViews.findById(ID)).thenReturn(Optional.empty());
        when(flightViews.countByRouteIdAndStatusNot("RT-OPOLIS", "CANCELED")).thenReturn(1L);

        assertTrue(projector.project(state(1, "SCHEDULED", T1)));

        ArgumentCaptor<FlightView> saved = ArgumentCaptor.forClass(FlightView.class);
        verify(flightViews).save(saved.capture());
        assertEquals("OPO", saved.getValue().getOrigin());
        assertEquals("LIS", saved.getValue().getDestination());
        assertEquals("CS-TTA", saved.getValue().getRegistrationNumber());

        ArgumentCaptor<RouteUtilizationView> util = ArgumentCaptor.forClass(RouteUtilizationView.class);
        verify(routeUtilization).save(util.capture());
        assertEquals(1, util.getValue().getFlightCount());
    }

    @Test
    void newerRevisionUpdatesTheView() {
        FlightView view = FlightView.from(state(1, "SCHEDULED", T1), "OPO", "LIS");
        when(flightViews.findById(ID)).thenReturn(Optional.of(view));

        assertTrue(projector.project(state(2, "DELAYED", T2)));
        assertEquals("DELAYED", view.getStatus());
        assertEquals(2, view.getRevision());
    }

    @Test
    void olderOrRepeatedEventIsIgnored() {
        FlightView view = FlightView.from(state(3, "DELAYED", T2), "OPO", "LIS");
        when(flightViews.findById(ID)).thenReturn(Optional.of(view));

        assertFalse(projector.project(state(2, "SCHEDULED", T1)), "older revision");
        assertFalse(projector.project(state(3, "DELAYED", T2)), "same event twice");
        assertEquals("DELAYED", view.getStatus());
        verify(routeUtilization, never()).save(any());
    }

    @Test
    void cancelledFlightsAreNotCountedInRouteUtilization() {
        FlightView view = FlightView.from(state(1, "SCHEDULED", T1), "OPO", "LIS");
        when(flightViews.findById(ID)).thenReturn(Optional.of(view));
        when(flightViews.countByRouteIdAndStatusNot("RT-OPOLIS", "CANCELED")).thenReturn(0L);

        projector.project(state(2, "CANCELED", T2));

        ArgumentCaptor<RouteUtilizationView> util = ArgumentCaptor.forClass(RouteUtilizationView.class);
        verify(routeUtilization).save(util.capture());
        assertEquals(0, util.getValue().getFlightCount());
    }

    @Test
    void routeUnknownLocallyStillProjectsTheFlight() {
        when(routeRefs.findById("RT-OPOLIS")).thenReturn(Optional.empty());
        when(flightViews.findById(ID)).thenReturn(Optional.empty());

        assertTrue(projector.project(state(1, "SCHEDULED", T1)));
        ArgumentCaptor<FlightView> saved = ArgumentCaptor.forClass(FlightView.class);
        verify(flightViews).save(saved.capture());
        assertNull(saved.getValue().getOrigin());
    }
}