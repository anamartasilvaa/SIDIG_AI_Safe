package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightEventPublisher;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.application.query.FlightProjector;
import pt.isep.psoft.aisafe.flight_operations.application.reference.ReferenceDataService;
import pt.isep.psoft.aisafe.flight_operations.domain.*;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

@Service
public class FlightCommandService {

    private static final Logger log = LoggerFactory.getLogger(FlightCommandService.class);

    private final ScheduledFlightRepository flights;
    private final AircraftRefRepository aircraftRefs;
    private final RouteRefRepository routeRefs;
    private final ReferenceDataService referenceData;
    private final FlightSchedulingPolicy policy;
    private final FlightEventPublisher events;
    private final FlightProjector projector;
    private final TransactionOperations tx;
    private final Clock clock;
    private final String instanceId;

    public FlightCommandService(ScheduledFlightRepository flights, AircraftRefRepository aircraftRefs,
                                RouteRefRepository routeRefs, ReferenceDataService referenceData,
                                FlightSchedulingPolicy policy, FlightEventPublisher events,
                                FlightProjector projector, TransactionOperations tx, Clock clock,
                                @Value("${app.instance-id}") String instanceId) {
        this.flights = flights;
        this.aircraftRefs = aircraftRefs;
        this.routeRefs = routeRefs;
        this.referenceData = referenceData;
        this.policy = policy;
        this.events = events;
        this.projector = projector;
        this.tx = tx;
        this.clock = clock;
        this.instanceId = instanceId;
    }

    private MissingFlightRecovery missingFlights;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMissingFlightRecovery(MissingFlightRecovery missingFlights) {
        this.missingFlights = missingFlights;
    }

    public ScheduledFlight schedule(CreateScheduledFlightDTO dto, String correlationId) {
        LocalDateTime now = LocalDateTime.now(clock);
        ScheduledFlight flight = new ScheduledFlight(RouteId.of(dto.routeId()),
                RegistrationNumber.of(dto.registrationNumber()), parseDateTime(dto.scheduledDateTime()),
                now, instanceId);
        RouteId routeId = flight.getRouteId();
        RegistrationNumber reg = flight.getAircraft();

        // Outside the main transaction: may call the owning service once (bootstrap / fallback).
        if (!referenceData.ensureRoute(routeId)) {
            throw new SchedulingRuleViolation("Route " + routeId + " does not exist.");
        }
        if (!referenceData.ensureAircraft(reg)) {
            throw new SchedulingRuleViolation("Aircraft " + reg + " does not exist.");
        }

        return tx.execute(status -> {
            // Row lock: schedules, grounding and replicated events for this aircraft are serialised here.
            AircraftRef aircraft = aircraftRefs.findForUpdate(reg.value())
                    .orElseThrow(() -> new SchedulingRuleViolation("Aircraft " + reg + " does not exist."));
            RouteRef route = routeRefs.findById(routeId.value())
                    .orElseThrow(() -> new SchedulingRuleViolation("Route " + routeId + " does not exist."));

            policy.checkAssignment(route.toRequirements(), aircraft.toCapabilities());

            List<FlightSlot> busy = flights.findByAircraft_Number(reg.value()).stream()
                    .map(ScheduledFlight::toSlot).toList();
            policy.findConflict(busy, flight.getScheduledDateTime()).ifPresent(c -> {
                throw new SchedulingRuleViolation("Aircraft " + reg + " already has flight " + c.flightId()
                        + " at " + c.scheduledDateTime() + " (minimum gap: "
                        + policy.minimumGap().toHours() + " h).");
            });

            ScheduledFlight saved = flights.save(flight);
            events.flightScheduled(saved, correlationId);
            projector.project(FlightState.of(saved));
            log.info("Flight {} scheduled (route {}, aircraft {}, rev {}).",
                    saved.getFlightId(), routeId, reg, saved.getRevision());
            return saved;
        });
    }

    public Optional<ScheduledFlight> changeStatus(String id, String statusText, String correlationId) {
        String flightId = FlightId.of(id).value();
        FlightStatus target = FlightStatus.parse(statusText);
        if (missingFlights != null) {
            missingFlights.fetchIfMissing(flightId);
        }
        return tx.execute(status -> flights.findByFlightId_Uuid(flightId).map(flight -> {
            FlightStatus previous = flight.getStatus();
            flight.changeStatus(target, LocalDateTime.now(clock));
            if (flight.getStatus() == previous) {
                return flight; // same status: nothing changed, no event
            }
            ScheduledFlight saved = flights.save(flight);
            events.flightStatusChanged(saved, previous.name(), correlationId);
            projector.project(FlightState.of(saved));
            log.info("Flight {}: {} -> {} (rev {}).", flightId, previous, target, saved.getRevision());
            return saved;
        }));
    }

    static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The scheduled date and time are required.");
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time '" + value
                    + "'. Expected ISO-8601, e.g. 2027-03-15T10:00:00");
        }
    }
}
