package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AvailabilityDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.domain.AircraftCapabilities;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightId;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightSchedulingPolicy;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightSlot;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;
import pt.isep.psoft.aisafe.flight_operations.domain.RegistrationNumber;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteId;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;
import pt.isep.psoft.aisafe.flight_operations.domain.SchedulingRuleViolation;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.DependencyUnavailableException;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.PeerClient;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ScheduledFlightService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledFlightService.class);
    private static final String INTERNAL = "/internal/scheduled-flights";

    private final ScheduledFlightRepository repository;
    private final PeerClient peers;
    private final FlightAssignmentValidator validator;
    private final FlightSchedulingPolicy policy;
    private final boolean requireCompleteConflictCheck;

    private final Map<String, Object> aircraftLocks = new ConcurrentHashMap<>();

    public ScheduledFlightService(ScheduledFlightRepository repository,
                                  PeerClient peers,
                                  FlightAssignmentValidator validator,
                                  FlightSchedulingPolicy policy,
                                  @Value("${app.scheduling.require-complete-conflict-check}") boolean requireCompleteConflictCheck) {
        this.repository = repository;
        this.peers = peers;
        this.validator = validator;
        this.policy = policy;
        this.requireCompleteConflictCheck = requireCompleteConflictCheck;
    }

    // ================================================================== LOCAL

    @Transactional(readOnly = true)
    public Optional<ScheduledFlightViewDTO> findLocalById(String id) {
        return repository.findByFlightId_Uuid(FlightId.of(id).value()).map(ScheduledFlightViewDTO::from);
    }

    @Transactional(readOnly = true)
    public List<ScheduledFlightViewDTO> findLocalByAircraft(String registration) {
        return repository.findByAircraft_Number(RegistrationNumber.of(registration).value()).stream()
                .map(ScheduledFlightViewDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RouteUtilizationDTO> findLocalRouteUtilization() {
        return repository.countFlightsGroupedByRoute().stream()
                .map(row -> new RouteUtilizationDTO((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    @Transactional
    public Optional<ScheduledFlightViewDTO> changeLocalStatus(String id, FlightStatus target) {
        return repository.findByFlightId_Uuid(FlightId.of(id).value()).map(flight -> {
            flight.changeStatus(target, LocalDateTime.now());
            ScheduledFlight saved = repository.save(flight);
            log.info("Voo {} -> {} (LOCAL)", id, target);
            return ScheduledFlightViewDTO.from(saved);
        });
    }

    // ============================================================ DISTRIBUIDO


    public Optional<ScheduledFlightViewDTO> findById(String id) {
        String flightId = FlightId.of(id).value();          // id invalido -> 400 antes de ir a rede
        Optional<ScheduledFlightViewDTO> local = findLocalById(flightId);
        if (local.isPresent()) {
            log.info("GET flight {} -> encontrado LOCALMENTE", flightId);
            return local;
        }
        log.info("GET flight {} -> nao existe localmente, a reencaminhar para peers", flightId);
        return peers.findFirst(INTERNAL + "/" + flightId, ScheduledFlightViewDTO.class);
    }

    public AggregatedResult<ScheduledFlightViewDTO> findByAircraft(String registration) {
        String reg = RegistrationNumber.of(registration).value();
        AggregatedResult<ScheduledFlightViewDTO> remote = peers.collectAll(
                INTERNAL + "/aircraft/" + reg, new ParameterizedTypeReference<List<ScheduledFlightViewDTO>>() {});

        Map<String, ScheduledFlightViewDTO> byId = new LinkedHashMap<>();
        findLocalByAircraft(reg).forEach(f -> byId.put(f.id(), f));
        remote.items().forEach(f -> byId.putIfAbsent(f.id(), f));

        List<ScheduledFlightViewDTO> merged = byId.values().stream()
                .sorted(Comparator.comparing(ScheduledFlightViewDTO::scheduledDateTime))
                .toList();
        return new AggregatedResult<>(merged, remote.unreachablePeers());
    }

    /** US229 distribuida: soma as contagens de cada rota em todas as replicas. */
    public AggregatedResult<RouteUtilizationDTO> routeUtilization() {
        AggregatedResult<RouteUtilizationDTO> remote = peers.collectAll(
                INTERNAL + "/route-utilization", new ParameterizedTypeReference<List<RouteUtilizationDTO>>() {});

        List<RouteUtilizationDTO> merged = Stream.concat(findLocalRouteUtilization().stream(), remote.items().stream())
                .collect(Collectors.groupingBy(RouteUtilizationDTO::routeId,
                        Collectors.summingLong(RouteUtilizationDTO::flightCount)))
                .entrySet().stream()
                .map(e -> new RouteUtilizationDTO(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(RouteUtilizationDTO::flightCount).reversed()
                        .thenComparing(RouteUtilizationDTO::routeId))
                .toList();
        return new AggregatedResult<>(merged, remote.unreachablePeers());
    }


    public AvailabilityDTO checkAvailability(String registration, String dateTime) {
        RegistrationNumber reg = RegistrationNumber.of(registration);
        LocalDateTime when = parseDateTime(dateTime);
        List<String> reasons = new ArrayList<>();

        String aircraftStatus = "UNKNOWN";
        try {
            Optional<AircraftCapabilities> aircraft = validator.findAircraft(reg);
            if (aircraft.isEmpty()) {
                reasons.add("Aircraft " + reg + " is not registered.");
            } else {
                aircraftStatus = aircraft.get().status() == null ? "UNKNOWN" : aircraft.get().status();
                if (!aircraft.get().isOperational()) {
                    reasons.add("Aircraft " + reg + " is " + aircraftStatus + ".");
                }
            }
        } catch (DependencyUnavailableException e) {
            reasons.add("Aircraft service unavailable: operational status could not be confirmed.");
        }

        AggregatedResult<ScheduledFlightViewDTO> flights = findByAircraft(reg.value());
        Optional<FlightSlot> conflict = policy.findConflict(toSlots(flights.items()), when);
        conflict.ifPresent(c -> reasons.add("Conflicts with flight " + c.flightId() + " at " + c.scheduledDateTime() + "."));
        if (flights.isPartial()) {
            reasons.add("Some replicas did not answer: conflicts could not be fully checked.");
        }

        return new AvailabilityDTO(reg.value(), when.toString(), reasons.isEmpty(), aircraftStatus,
                conflict.map(FlightSlot::flightId).orElse(null), reasons, flights.unreachablePeers());
    }

    public Optional<ScheduledFlightViewDTO> changeStatus(String id, String statusText) {
        String flightId = FlightId.of(id).value();
        FlightStatus target = FlightStatus.parse(statusText);

        Optional<ScheduledFlightViewDTO> local = changeLocalStatus(flightId, target);
        if (local.isPresent()) {
            return local;
        }
        log.info("Voo {} nao e desta replica: a reencaminhar mudanca de estado para os peers", flightId);
        return peers.forwardPut(INTERNAL + "/" + flightId + "/status", Map.of("status", target.name()),
                ScheduledFlightViewDTO.class);
    }

    // ================================================================== WRITE

    public ScheduledFlightViewDTO scheduleFlight(CreateScheduledFlightDTO dto) {
        ScheduledFlight flight = new ScheduledFlight(
                RouteId.of(dto.routeId()),
                RegistrationNumber.of(dto.registrationNumber()),
                parseDateTime(dto.scheduledDateTime()),
                LocalDateTime.now());

        String reg = flight.getAircraft().value();
        synchronized (aircraftLocks.computeIfAbsent(reg, k -> new Object())) {
            validator.validate(flight.getRouteId(), flight.getAircraft());

            AggregatedResult<ScheduledFlightViewDTO> existing = findByAircraft(reg);
            if (existing.isPartial()) {
                if (requireCompleteConflictCheck) {
                    throw new DependencyUnavailableException("Cannot guarantee the aircraft is free: replicas "
                            + existing.unreachablePeers() + " did not answer. Try again later.");
                }
                log.warn("Conflitos verificados com resposta parcial (peers em baixo: {})", existing.unreachablePeers());
            }
            policy.findConflict(toSlots(existing.items()), flight.getScheduledDateTime())
                    .ifPresent(c -> {
                        throw new SchedulingRuleViolation("Aircraft " + reg + " already has flight " + c.flightId()
                                + " at " + c.scheduledDateTime() + " (minimum gap: "
                                + policy.minimumGap().toHours() + " h).");
                    });
            ScheduledFlight saved = repository.save(flight);
            log.info("Voo {} criado LOCALMENTE (rota {}, aviao {})",
                    saved.getFlightId(), saved.getRouteId(), saved.getAircraft());
            return ScheduledFlightViewDTO.from(saved);
        }
    }

    // ================================================================ helpers

    private static List<FlightSlot> toSlots(List<ScheduledFlightViewDTO> flights) {
        return flights.stream().map(ScheduledFlightViewDTO::toSlot).toList();
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The scheduled date and time are required.");
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time '" + value + "'. Expected ISO-8601, e.g. 2027-03-15T10:00:00");
        }
    }
}