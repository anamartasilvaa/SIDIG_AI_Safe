package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightEventPublisher;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Closes the "late flight" gap of the grounding: a flight accepted on a replica that had not yet
 * received the aircraft status change (or applied later from an event) would stay on a grounded
 * aircraft. Periodically, for every aircraft known locally as not ACTIVE that still has future
 * flights to move, a GroundingRequested is written to the outbox; the shared work queue
 * fo.grounding then makes ONE replica run the (idempotent) GroundingService.
 * Flights already handled (DELAYED with NO_REPLACEMENT_AIRCRAFT) are not requested again, and the
 * same aircraft is requested at most once per cool-down period.
 */
@Component
public class GroundingReconciler {

    static final Duration COOL_DOWN = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(GroundingReconciler.class);

    private final AircraftRefRepository aircraftRefs;
    private final ScheduledFlightRepository flights;
    private final FlightEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;
    private final Map<String, Instant> lastRequest = new ConcurrentHashMap<>();

    public GroundingReconciler(AircraftRefRepository aircraftRefs, ScheduledFlightRepository flights,
                               FlightEventPublisher events, TransactionOperations tx, Clock clock) {
        this.aircraftRefs = aircraftRefs;
        this.flights = flights;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.grounding.reconcile-interval:30s}",
               initialDelayString = "${app.grounding.reconcile-interval:30s}")
    public void reconcile() {
        Instant now = clock.instant();
        LocalDateTime nowLocal = LocalDateTime.now(clock);
        for (AircraftRef ref : aircraftRefs.findAll()) {
            if (ref.isActive()) {
                lastRequest.remove(ref.getRegistrationNumber());
                continue;
            }
            String reg = ref.getRegistrationNumber();
            long pending = flights.findByAircraft_Number(reg).stream()
                    .filter(ScheduledFlight::canChangeAircraft)
                    .filter(f -> f.getScheduledDateTime().isAfter(nowLocal))
                    .filter(f -> !GroundingService.NO_REPLACEMENT.equals(f.getStatusReason()))
                    .count();
            if (pending == 0) {
                continue;
            }
            Instant last = lastRequest.get(reg);
            if (last != null && last.plus(COOL_DOWN).isAfter(now)) {
                continue;
            }
            tx.executeWithoutResult(status -> events.groundingRequested(reg, "reconcile-" + reg + "-" + now.toEpochMilli()));
            lastRequest.put(reg, now);
            log.warn("GROUNDING RECONCILE: {} is not ACTIVE and still has {} future flight(s); grounding requested.",
                    reg, pending);
        }
    }
}
