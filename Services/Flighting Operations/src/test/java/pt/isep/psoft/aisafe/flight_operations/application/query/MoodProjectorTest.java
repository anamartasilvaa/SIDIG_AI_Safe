package pt.isep.psoft.aisafe.flight_operations.application.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.AircraftMoodViewRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.FlightViewRepository;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MoodProjectorTest {

    private static final Instant NOW = Instant.parse("2027-03-10T12:00:00Z");

    private final List<FlightView> views = new ArrayList<>();
    private AircraftRefRepository aircraftRefs;
    private MoodProjector projector;

    @BeforeEach
    void setUp() {
        FlightViewRepository flightViews = mock(FlightViewRepository.class);
        aircraftRefs = mock(AircraftRefRepository.class);
        AircraftMoodViewRepository moods = mock(AircraftMoodViewRepository.class);
        projector = new MoodProjector(flightViews, aircraftRefs, moods, Clock.fixed(NOW, ZoneOffset.UTC));
        when(flightViews.findByRegistrationNumberOrderByScheduledDateTimeAsc("CS-TTA")).thenReturn(views);
        when(moods.findById(anyString())).thenReturn(Optional.empty());
        when(moods.save(any())).thenAnswer(inv -> inv.getArgument(0));
        active("ACTIVE");
    }

    private void active(String status) {
        when(aircraftRefs.findById("CS-TTA")).thenReturn(Optional.of(AircraftRef.create("CS-TTA", null, status, null, null, 1, NOW)));
    }

    private void flight(String when, String status) {
        views.add(FlightView.from(new FlightState(UUID.randomUUID().toString(), "RT-OPOLIS", "CS-TTA",
                LocalDateTime.parse(when), null, null, status, null, 1, NOW, NOW, "fo-1"), null, null));
    }

    @Test
    void happyByDefault() {
        assertEquals(AircraftMood.HAPPY, projector.recompute("CS-TTA").getMood());
    }

    @Test
    void sleepingWhenNotActiveEvenIfBusy() {
        active("UNDER_MAINTENANCE");
        flight("2027-03-10T14:00:00", "SCHEDULED");
        flight("2027-03-10T17:00:00", "SCHEDULED");
        flight("2027-03-10T20:00:00", "SCHEDULED");
        assertEquals(AircraftMood.SLEEPING, projector.recompute("CS-TTA").getMood());
    }

    @Test
    void grumpyWithTwoRecentDelaysOrCancellations() {
        flight("2027-03-08T10:00:00", "DELAYED");
        flight("2027-03-09T10:00:00", "CANCELED");
        AircraftMoodView v = projector.recompute("CS-TTA");
        assertEquals(AircraftMood.GRUMPY, v.getMood());
        assertEquals(2, v.getDelayedOrCanceled());
    }

    @Test
    void oldDelaysDoNotCount() {
        flight("2027-02-01T10:00:00", "DELAYED");
        flight("2027-02-02T10:00:00", "CANCELED");
        assertEquals(AircraftMood.HAPPY, projector.recompute("CS-TTA").getMood());
    }

    @Test
    void busyWithThreeFlightsInTheNext24h() {
        flight("2027-03-10T14:00:00", "SCHEDULED");
        flight("2027-03-10T17:00:00", "DELAYED");
        flight("2027-03-11T08:00:00", "SCHEDULED");
        flight("2027-03-12T08:00:00", "SCHEDULED");   // outside the 24 h window
        AircraftMoodView v = projector.recompute("CS-TTA");
        assertEquals(AircraftMood.BUSY, v.getMood());
        assertEquals(3, v.getFlightsNext24h());
    }
}
