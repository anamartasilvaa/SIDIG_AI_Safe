package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AvailabilityDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduleResultDTO;
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
import pt.isep.psoft.aisafe.flight_operations.infrastructure.FlightReplicator;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.PeerClient;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ScheduledFlightService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledFlightService.class);
    private static final String INTERNAL = "/internal/scheduled-flights";

    private final ScheduledFlightRepository repository;
    private final PeerClient peers;
    private final FlightReplicator replicator;
    private final FlightAssignmentValidator validator;
    private final FlightSchedulingPolicy policy;
    private final boolean requireCompleteConflictCheck;

    private final Map<String, Object> aircraftLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> flightLocks = new ConcurrentHashMap<>();

    public ScheduledFlightService(ScheduledFlightRepository repository,
                                  PeerClient peers,
                                  FlightReplicator replicator,
                                  FlightAssignmentValidator validator,
                                  FlightSchedulingPolicy policy,
                                  @Value("${app.scheduling.require-complete-conflict-check:false}") boolean requireCompleteConflictCheck) {
        this.repository = repository;
        this.peers = peers;
        this.replicator = replicator;
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
    public List<ScheduledFlightViewDTO> findLocalAll() {
        return repository.findAll().stream().map(ScheduledFlightViewDTO::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RouteUtilizationDTO> findLocalRouteUtilization() {
        return repository.countFlightsGroupedByRoute().stream()
                .map(row -> new RouteUtilizationDTO((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    public Optional<ScheduledFlightViewDTO> changeLocalStatus(String id, FlightStatus target) {
        String flightId = FlightId.of(id).value();
        synchronized (flightLock(flightId)) {
            return repository.findByFlightId_Uuid(flightId).map(flight -> {
                flight.changeStatus(target, LocalDateTime.now());
                ScheduledFlight saved = repository.save(flight);
                log.info("Voo {} -> {} (LOCAL, rev {})", flightId, target, saved.getRevision());
                return ScheduledFlightViewDTO.from(saved);
            });
        }
    }

    public Optional<ScheduledFlightViewDTO> applyStatusLocally(String id, FlightStatus target) {
        Optional<ScheduledFlightViewDTO> changed = changeLocalStatus(id, target);
        changed.ifPresent(replicator::replicateAcknowledged);
        return changed;
    }

    public boolean applyReplica(ScheduledFlightViewDTO replica) {
        String flightId = FlightId.of(replica.id()).value();
        synchronized (flightLock(flightId)) {
            Optional<ScheduledFlight> existing = repository.findByFlightId_Uuid(flightId);
            if (existing.isEmpty()) {
                ScheduledFlight created = repository.save(replica.toAggregate());
                log.info("REPLICA recebida: voo {} criado (rev {}, {})", flightId, created.getRevision(), created.getStatus());
                warnIfConflict(created);
                return true;
            }
            ScheduledFlight flight = existing.get();
            boolean changed = flight.mergeReplica(RegistrationNumber.of(replica.registrationNumber()),
                    FlightStatus.parse(replica.status()),
                    parse(replica.actualDepartureDateTime()), parse(replica.actualArrivalDateTime()),
                    replica.revision(), replica.lastModified());
            if (changed) {
                repository.save(flight);
                log.info("REPLICA recebida: voo {} atualizado para rev {} ({})", flightId, replica.revision(), replica.status());
            } else {
                log.debug("REPLICA recebida: voo {} rev {} ignorada (copia local igual ou mais recente)",
                        flightId, replica.revision());
            }
            return changed;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncFromPeersOnStartup() {
        if (!replicator.isEnabled()) {
            return;
        }
        AggregatedResult<ScheduledFlightViewDTO> remote = peers.collectAll(INTERNAL,
                new ParameterizedTypeReference<List<ScheduledFlightViewDTO>>() {});
        long changed = remote.items().stream().filter(this::applyReplicaSafely).count();
        log.info("SYNC no arranque: {} copia(s) recebida(s) dos peers, {} aplicada(s). Peers sem resposta: {}",
                remote.items().size(), changed, remote.unreachablePeers());
    }


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

        List<ScheduledFlightViewDTO> merged = mergeNewest(findLocalByAircraft(reg), remote.items()).stream()
                .sorted(Comparator.comparing(ScheduledFlightViewDTO::scheduledDateTime))
                .toList();
        return new AggregatedResult<>(merged, remote.unreachablePeers());
    }
    public AggregatedResult<RouteUtilizationDTO> routeUtilization() {
        AggregatedResult<ScheduledFlightViewDTO> remote = peers.collectAll(
                INTERNAL, new ParameterizedTypeReference<List<ScheduledFlightViewDTO>>() {});

        List<RouteUtilizationDTO> report = mergeNewest(findLocalAll(), remote.items()).stream()
                .collect(Collectors.groupingBy(ScheduledFlightViewDTO::routeId, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new RouteUtilizationDTO(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(RouteUtilizationDTO::flightCount).reversed()
                        .thenComparing(RouteUtilizationDTO::routeId))
                .toList();
        return new AggregatedResult<>(report, remote.unreachablePeers());
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

        Optional<ScheduledFlightViewDTO> local = applyStatusLocally(flightId, target);
        if (local.isPresent()) {
            return local;
        }
        log.info("Voo {} ainda nao existe nesta replica: a reencaminhar mudança de estado para os peers", flightId);
        return peers.forwardPut(INTERNAL + "/" + flightId + "/status", Map.of("status", target.name()),
                ScheduledFlightViewDTO.class);
    }

    // ================================================================== WRITE

    public ScheduleResultDTO scheduleFlight(CreateScheduledFlightDTO dto) {
        ScheduledFlight flight = new ScheduledFlight(
                RouteId.of(dto.routeId()),
                RegistrationNumber.of(dto.registrationNumber()),
                parseDateTime(dto.scheduledDateTime()),
                LocalDateTime.now());

        String reg = flight.getAircraft().value();
        ScheduledFlightViewDTO created;
        List<String> unreachable;
        synchronized (aircraftLocks.computeIfAbsent(reg, k -> new Object())) {
            validator.validate(flight.getRouteId(), flight.getAircraft());

            AggregatedResult<ScheduledFlightViewDTO> existing = findByAircraft(reg);
            unreachable = existing.unreachablePeers();
            if (existing.isPartial()) {
                if (requireCompleteConflictCheck) {
                    throw new DependencyUnavailableException("Cannot guarantee the aircraft is free: replicas "
                            + unreachable + " did not answer. Try again later.");
                }
                log.warn("AP: agendamento aceite com verificação PARCIAL de conflitos (peers sem resposta: {})",
                        unreachable);
            }
            policy.findConflict(toSlots(existing.items()), flight.getScheduledDateTime())
                    .ifPresent(c -> {
                        throw new SchedulingRuleViolation("Aircraft " + reg + " already has flight " + c.flightId()
                                + " at " + c.scheduledDateTime() + " (minimum gap: "
                                + policy.minimumGap().toHours() + " h).");
                    });
            ScheduledFlight saved = repository.save(flight);
            created = ScheduledFlightViewDTO.from(saved);
            log.info("Voo {} criado LOCALMENTE (rota {}, avião {})",
                    saved.getFlightId(), saved.getRouteId(), saved.getAircraft());
        }
        if (!replicator.replicateAcknowledged(created)) {
            log.warn("AP: voo {} aceite sem confirmação de nenhum peer (fica em fila para nova tentativa)",
                    created.id());
        }
        return new ScheduleResultDTO(created, unreachable);
    }

    // ================================================================ helpers

    private boolean applyReplicaSafely(ScheduledFlightViewDTO replica) {
        try {
            return applyReplica(replica);
        } catch (RuntimeException e) {
            log.warn("SYNC: copia do voo {} ignorada ({})", replica.id(), e.getMessage());
            return false;
        }
    }

    private void warnIfConflict(ScheduledFlight received) {
        if (!received.getStatus().occupiesAircraft()) {
            return;
        }
        List<FlightSlot> others = repository.findByAircraft_Number(received.getAircraft().value()).stream()
                .filter(f -> !f.getFlightId().equals(received.getFlightId()))
                .map(ScheduledFlight::toSlot)
                .toList();
        policy.findConflict(others, received.getScheduledDateTime()).ifPresent(c ->
                log.warn("CONFLITO detetado apos replicação: voos {} e {} do avião {} a menos de {} h",
                        received.getFlightId(), c.flightId(), received.getAircraft(), policy.minimumGap().toHours()));
    }

    private static List<ScheduledFlightViewDTO> mergeNewest(List<ScheduledFlightViewDTO> local,
                                                            List<ScheduledFlightViewDTO> remote) {
        return List.copyOf(Stream.concat(local.stream(), remote.stream())
                .collect(Collectors.toMap(ScheduledFlightViewDTO::id, Function.identity(),
                        ScheduledFlightViewDTO::newest, LinkedHashMap::new))
                .values());
    }

    private Object flightLock(String flightId) {
        return flightLocks.computeIfAbsent(flightId, k -> new Object());
    }

    private static List<FlightSlot> toSlots(List<ScheduledFlightViewDTO> flights) {
        return flights.stream().map(ScheduledFlightViewDTO::toSlot).toList();
    }

    private static LocalDateTime parse(String value) {
        return value == null ? null : LocalDateTime.parse(value);
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

    // ============================================================ GROUNDING (US119 / US222)
    public List<String> groundAircraft(String registration, List<String> candidateRegistrations) {
        RegistrationNumber grounded = RegistrationNumber.of(registration);
        List<RegistrationNumber> candidates = (candidateRegistrations == null ? List.<String>of() : candidateRegistrations)
                .stream()
                .filter(c -> c != null && !c.isBlank())
                .map(RegistrationNumber::of)
                .filter(c -> !c.equals(grounded))
                .distinct()
                .toList();
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledFlightViewDTO> affected = findByAircraft(grounded.value()).items().stream()
                .filter(f -> {
                    FlightStatus st = FlightStatus.parse(f.status());
                    return (st == FlightStatus.SCHEDULED || st == FlightStatus.DELAYED)
                            && LocalDateTime.parse(f.scheduledDateTime()).isAfter(now);
                })
                .toList();

        List<String> report = new ArrayList<>();
        if (affected.isEmpty()) {
            report.add("Aircraft " + grounded + " has no upcoming flights to reassign.");
        }
        for (ScheduledFlightViewDTO flight : affected) {
            report.add(reassignToFirstFreeCandidate(flight, grounded, candidates));
        }
        log.warn("GROUNDING {}: {}", grounded, report);
        return report;
    }

    private String reassignToFirstFreeCandidate(ScheduledFlightViewDTO flight, RegistrationNumber grounded,
                                                List<RegistrationNumber> candidates) {
        LocalDateTime when = LocalDateTime.parse(flight.scheduledDateTime());
        String label = "Flight " + flight.id() + " (" + flight.routeId() + ", " + flight.scheduledDateTime() + ")";
        for (RegistrationNumber candidate : candidates) {
            synchronized (aircraftLocks.computeIfAbsent(candidate.value(), k -> new Object())) {
                List<FlightSlot> busy = toSlots(findByAircraft(candidate.value()).items());
                if (policy.findConflict(busy, when).isPresent()) {
                    continue;
                }
                Optional<ScheduledFlightViewDTO> moved = reassignLocally(flight.id(), candidate);
                if (moved.isPresent()) {
                    return label + " reassigned from " + grounded + " to " + candidate + ".";
                }
                return label + " is not stored on this replica yet; not reassigned.";
            }
        }
        return label + " kept on " + grounded + ": no candidate aircraft is free at that time.";
    }

    private Optional<ScheduledFlightViewDTO> reassignLocally(String id, RegistrationNumber newAircraft) {
        String flightId = FlightId.of(id).value();
        Optional<ScheduledFlightViewDTO> moved;
        synchronized (flightLock(flightId)) {
            moved = repository.findByFlightId_Uuid(flightId).map(flight -> {
                flight.reassignAircraft(newAircraft);
                ScheduledFlight saved = repository.save(flight);
                log.info("Voo {} retribuído ao avião {} (rev {})", flightId, newAircraft, saved.getRevision());
                return ScheduledFlightViewDTO.from(saved);
            });
        }
        moved.ifPresent(replicator::replicate);
        return moved;
    }

}
