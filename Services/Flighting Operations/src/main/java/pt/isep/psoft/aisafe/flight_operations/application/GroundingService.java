package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightEventPublisher;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.application.query.FlightProjector;
import pt.isep.psoft.aisafe.flight_operations.domain.*;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.RouteRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Grounding (US119/US222): the future SCHEDULED/DELAYED flights of a grounded aircraft move to
 * the first candidate that is ACTIVE, satisfies the route (range and capacity) and has no 2 h
 * conflict. Candidates come from the local reference data, in a deterministic order.
 * If no candidate is free, the flight becomes DELAYED with reason NO_REPLACEMENT_AIRCRAFT.
 * Idempotent: running it twice moves nothing the second time.
 */
@Service
public class GroundingService {

    public static final String NO_REPLACEMENT = "NO_REPLACEMENT_AIRCRAFT";
    private static final Logger log = LoggerFactory.getLogger(GroundingService.class);

    private final ScheduledFlightRepository flights;
    private final AircraftRefRepository aircraftRefs;
    private final RouteRefRepository routeRefs;
    private final FlightSchedulingPolicy policy;
    private final FlightEventPublisher events;
    private final FlightProjector projector;
    private final Clock clock;

    public GroundingService(ScheduledFlightRepository flights, AircraftRefRepository aircraftRefs,
                            RouteRefRepository routeRefs, FlightSchedulingPolicy policy,
                            FlightEventPublisher events, FlightProjector projector, Clock clock) {
        this.flights = flights;
        this.aircraftRefs = aircraftRefs;
        this.routeRefs = routeRefs;
        this.policy = policy;
        this.events = events;
        this.projector = projector;
        this.clock = clock;
    }

    /** @return one sentence per affected flight (report). */
    @Transactional
    public List<String> ground(String registration, String correlationId) {
        RegistrationNumber grounded = RegistrationNumber.of(registration);
        aircraftRefs.findForUpdate(grounded.value());
        LocalDateTime now = LocalDateTime.now(clock);

        List<ScheduledFlight> affected = flights.findByAircraft_Number(grounded.value()).stream()
                .filter(ScheduledFlight::canChangeAircraft)
                .filter(f -> f.getScheduledDateTime().isAfter(now))
                .sorted(Comparator.comparing(ScheduledFlight::getScheduledDateTime))
                .toList();

        List<String> report = new ArrayList<>();
        if (affected.isEmpty()) {
            report.add("Aircraft " + grounded + " has no upcoming flights to reassign.");
        }
        for (ScheduledFlight flight : affected) {
            report.add(reassign(flight, grounded, correlationId));
        }
        log.warn("GROUNDING {}: {}", grounded, report);
        return report;
    }

    private String reassign(ScheduledFlight flight, RegistrationNumber grounded, String correlationId) {
        String label = "Flight " + flight.getFlightId() + " (" + flight.getRouteId() + ", "
                + flight.getScheduledDateTime() + ")";
        Optional<RouteRequirements> route = routeRefs.findById(flight.getRouteId().value()).map(RouteRef::toRequirements);

        for (AircraftRef candidate : aircraftRefs.findByStatusOrderByRegistrationNumberAsc(AircraftRef.ACTIVE)) {
            if (candidate.getRegistrationNumber().equals(grounded.value()) || !fits(route, candidate)) {
                continue;
            }
            aircraftRefs.findForUpdate(candidate.getRegistrationNumber());
            List<FlightSlot> busy = flights.findByAircraft_Number(candidate.getRegistrationNumber()).stream()
                    .map(ScheduledFlight::toSlot).toList();
            if (policy.findConflict(busy, flight.getScheduledDateTime()).isPresent()) {
                continue;
            }
            flight.reassignAircraft(RegistrationNumber.of(candidate.getRegistrationNumber()));
            ScheduledFlight saved = flights.save(flight);
            events.flightReassigned(saved, grounded.value(), correlationId);
            projector.project(FlightState.of(saved));
            return label + " reassigned from " + grounded + " to " + candidate.getRegistrationNumber() + ".";
        }

        if (flight.getStatus() == FlightStatus.SCHEDULED) {
            String previous = flight.getStatus().name();
            flight.changeStatus(FlightStatus.DELAYED, LocalDateTime.now(clock), NO_REPLACEMENT);
            ScheduledFlight saved = flights.save(flight);
            events.flightStatusChanged(saved, previous, correlationId);
            projector.project(FlightState.of(saved));
        }
        return label + " kept on " + grounded + " and DELAYED: no candidate aircraft is free at that time.";
    }

    private boolean fits(Optional<RouteRequirements> route, AircraftRef candidate) {
        if (route.isEmpty()) {
            return true; // route unknown locally: only the time conflict can be checked
        }
        try {
            policy.checkAssignment(route.get(), candidate.toCapabilities());
            return true;
        } catch (SchedulingRuleViolation e) {
            return false;
        }
    }
}
