package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightEventPublisher;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.application.query.FlightProjector;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightSchedulingPolicy;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Conflicts created during a partition (AP): two replicas accepted flights of the same aircraft
 * less than the minimum gap apart. Every replica elects the SAME winner, the oldest
 * (createdAt, flightId), so no coordination is needed. The loser is cancelled with a visible
 * reason (compensation). If several replicas detect it, they produce the same target state,
 * so the duplicated compensations converge.
 */
@Component
public class ConflictResolver {

    public static final String REASON_PREFIX = "SCHEDULE_CONFLICT_WITH ";
    private static final Logger log = LoggerFactory.getLogger(ConflictResolver.class);
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final ScheduledFlightRepository flights;
    private final FlightSchedulingPolicy policy;
    private final FlightEventPublisher events;
    private final FlightProjector projector;
    private final Clock clock;

    public ConflictResolver(ScheduledFlightRepository flights, FlightSchedulingPolicy policy,
                            FlightEventPublisher events, FlightProjector projector, Clock clock) {
        this.flights = flights;
        this.policy = policy;
        this.events = events;
        this.projector = projector;
        this.clock = clock;
    }

    /**
     * Must run inside the transaction that applied {@code changed} (with the aircraft row locked).
     * @return the flights cancelled by this call.
     */
    public List<ScheduledFlight> resolve(ScheduledFlight changed, String correlationId) {
        List<ScheduledFlight> cancelled = new ArrayList<>();
        if (!changed.getStatus().occupiesAircraft()) {
            return cancelled;
        }
        Duration gap = policy.minimumGap();
        for (ScheduledFlight other : flights.findByAircraft_Number(changed.getAircraft().value())) {
            if (other.getFlightId().equals(changed.getFlightId()) || !other.getStatus().occupiesAircraft()) {
                continue;
            }
            if (Duration.between(other.getScheduledDateTime(), changed.getScheduledDateTime()).abs().compareTo(gap) >= 0) {
                continue;
            }
            ScheduledFlight winner = changed.winsConflictAgainst(other) ? changed : other;
            ScheduledFlight loser = winner == changed ? other : changed;
            if (!loser.getStatus().canTransitionTo(FlightStatus.CANCELED)) {
                log.warn("CONFLICT {} vs {} on {}: loser is {} and cannot be cancelled (manual action needed).",
                        winner.getFlightId(), loser.getFlightId(), changed.getAircraft(), loser.getStatus());
                continue;
            }
            String previous = loser.getStatus().name();
            loser.changeStatus(FlightStatus.CANCELED, LocalDateTime.now(clock),
                    REASON_PREFIX + winner.getFlightId().value());
            ScheduledFlight saved = flights.save(loser);
            events.flightStatusChanged(saved, previous, correlationId);
            projector.project(FlightState.of(saved));
            cancelled.add(saved);
            log.warn("CONFLICT on {}: flight {} kept, flight {} cancelled (compensation).",
                    changed.getAircraft(), winner.getFlightId(), loser.getFlightId());
            audit.info("User: system - Action: COMPENSATION CANCELED - Target ID: {} - Reason: conflict with {}",
                    loser.getFlightId(), winner.getFlightId());
            if (loser == changed) {
                break; // the changed flight is cancelled: it no longer occupies the aircraft
            }
        }
        return cancelled;
    }
}
