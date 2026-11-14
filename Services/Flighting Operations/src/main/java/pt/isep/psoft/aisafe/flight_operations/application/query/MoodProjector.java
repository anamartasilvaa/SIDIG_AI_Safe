package pt.isep.psoft.aisafe.flight_operations.application.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.AircraftMoodViewRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.FlightViewRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Projection behind GET /api/v2/unicorns. The mood is never written by a client: it is derived
 * from data that arrived by events (flight views and aircraft references).
 * Rules (first that applies): SLEEPING (aircraft not ACTIVE) > GRUMPY (>= 2 flights DELAYED or
 * CANCELED in the last 7 days) > BUSY (>= 3 flights in the next 24 h) > HAPPY.
 * Recomputed on every flight change of the aircraft and periodically, because "next 24 h" and
 * "last 7 days" move with time even without events.
 */
@Component
public class MoodProjector {

    static final int GRUMPY_THRESHOLD = 2;
    static final int BUSY_THRESHOLD = 3;
    private static final Logger log = LoggerFactory.getLogger(MoodProjector.class);

    private final FlightViewRepository flightViews;
    private final AircraftRefRepository aircraftRefs;
    private final AircraftMoodViewRepository moods;
    private final Clock clock;

    public MoodProjector(FlightViewRepository flightViews, AircraftRefRepository aircraftRefs,
                         AircraftMoodViewRepository moods, Clock clock) {
        this.flightViews = flightViews;
        this.aircraftRefs = aircraftRefs;
        this.moods = moods;
        this.clock = clock;
    }

    @Transactional
    public AircraftMoodView recompute(String registration) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<FlightView> flights = flightViews.findByRegistrationNumberOrderByScheduledDateTimeAsc(registration);

        int delayedOrCanceled = (int) flights.stream()
                .filter(f -> f.getStatus().equals(FlightStatus.DELAYED.name())
                        || f.getStatus().equals(FlightStatus.CANCELED.name()))
                .filter(f -> !f.getScheduledDateTime().isBefore(now.minusDays(7)))
                .count();
        int next24h = (int) flights.stream()
                .filter(f -> FlightStatus.parse(f.getStatus()).occupiesAircraft())
                .filter(f -> !f.getScheduledDateTime().isBefore(now) && f.getScheduledDateTime().isBefore(now.plusHours(24)))
                .count();
        Optional<AircraftRef> aircraft = aircraftRefs.findById(registration);

        AircraftMood mood;
        String reason;
        if (aircraft.isPresent() && !aircraft.get().isActive()) {
            mood = AircraftMood.SLEEPING;
            reason = "Aircraft is " + aircraft.get().getStatus() + ".";
        } else if (delayedOrCanceled >= GRUMPY_THRESHOLD) {
            mood = AircraftMood.GRUMPY;
            reason = delayedOrCanceled + " flights delayed or cancelled in the last 7 days.";
        } else if (next24h >= BUSY_THRESHOLD) {
            mood = AircraftMood.BUSY;
            reason = next24h + " flights in the next 24 h.";
        } else {
            mood = AircraftMood.HAPPY;
            reason = "No recent delays and a light schedule.";
        }

        AircraftMoodView view = moods.findById(registration).orElseGet(() -> new AircraftMoodView(registration));
        view.update(mood, reason, delayedOrCanceled, next24h, clock.instant());
        return moods.save(view);
    }

    /** Periodic refresh of every known aircraft (time-based rules, aircraft without flights). */
    @Scheduled(fixedDelayString = "${app.mood.refresh-interval:5m}",
               initialDelayString = "${app.mood.refresh-interval:5m}")
    @Transactional
    public void refreshAll() {
        Set<String> registrations = new TreeSet<>(flightViews.findDistinctRegistrationNumbers());
        aircraftRefs.findAll().forEach(a -> registrations.add(a.getRegistrationNumber()));
        registrations.forEach(this::recompute);
        log.debug("Moods refreshed for {} aircraft.", registrations.size());
    }
}
