package pt.isep.psoft.aisafe.flight_operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.PeerClient;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PASSO 5 - Separacao clara entre:
 *   - consultas LOCAIS  (so a BD desta instancia; usadas pelo controller /internal)
 *   - consultas DISTRIBUIDAS (local + peers; usadas pelo controller publico)
 * As distribuidas NAO sao @Transactional: nao faz sentido manter uma transacao
 * aberta enquanto se espera pela rede.
 */
@Service
public class ScheduledFlightService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledFlightService.class);
    private static final String INTERNAL = "/internal/scheduled-flights";

    private final ScheduledFlightRepository repository;
    private final PeerClient peers;
    private final FlightAssignmentValidator validator;
    private final Duration minGap;

    public ScheduledFlightService(ScheduledFlightRepository repository,
                                  PeerClient peers,
                                  FlightAssignmentValidator validator,
                                  @Value("${app.scheduling.min-gap-hours}") long minGapHours) {
        this.repository = repository;
        this.peers = peers;
        this.validator = validator;
        this.minGap = Duration.ofHours(minGapHours);
    }

    // ------------------------------------------------------------------ LOCAL

    @Transactional(readOnly = true)
    public Optional<ScheduledFlightViewDTO> findLocalById(String id) {
        return repository.findById(id).map(ScheduledFlightViewDTO::from);
    }

    @Transactional(readOnly = true)
    public List<ScheduledFlightViewDTO> findLocalByAircraft(String registration) {
        return repository.findByAircraftRegistration(normalize(registration)).stream()
                .map(ScheduledFlightViewDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RouteUtilizationDTO> findLocalRouteUtilization() {
        return repository.countFlightsGroupedByRoute().stream()
                .map(row -> new RouteUtilizationDTO((String) row[0], (Long) row[1]))
                .toList();
    }

    // ------------------------------------------------------------ DISTRIBUIDO

    /** 1) procura localmente  2) se nao existir, pergunta aos peers  3) vazio -> 404 */
    public Optional<ScheduledFlightViewDTO> findById(String id) {
        Optional<ScheduledFlightViewDTO> local = findLocalById(id);
        if (local.isPresent()) {
            log.info("GET flight {} -> encontrado LOCALMENTE", id);
            return local;
        }
        log.info("GET flight {} -> nao existe localmente, a reencaminhar para peers", id);
        return peers.findFirst(INTERNAL + "/" + id, ScheduledFlightViewDTO.class);
    }

    /** Local + todos os peers, sem duplicados. */
    public AggregatedResult<ScheduledFlightViewDTO> findByAircraft(String registration) {
        String reg = normalize(registration);
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

        List<RouteUtilizationDTO> all = new ArrayList<>(findLocalRouteUtilization());
        all.addAll(remote.items());

        List<RouteUtilizationDTO> merged = all.stream()
                .collect(Collectors.groupingBy(RouteUtilizationDTO::routeId,
                        Collectors.summingLong(RouteUtilizationDTO::flightCount)))
                .entrySet().stream()
                .map(e -> new RouteUtilizationDTO(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(RouteUtilizationDTO::flightCount).reversed()
                        .thenComparing(RouteUtilizationDTO::routeId))
                .toList();
        return new AggregatedResult<>(merged, remote.unreachablePeers());
    }

    // ------------------------------------------------------------------ WRITE

    public ScheduledFlightViewDTO scheduleFlight(CreateScheduledFlightDTO dto) {
        String reg = normalize(dto.registrationNumber());
        String routeId = dto.routeId().trim();
        LocalDateTime when = LocalDateTime.parse(dto.scheduledDateTime());   // invalido -> 400 no handler

        // PASSO 6a: rota e aviao existem e sao compativeis (chamadas aos outros servicos)
        validator.validate(routeId, reg);

        // PASSO 6b: o aviao nao pode ter outro voo demasiado perto (consulta TODAS as replicas)
        AggregatedResult<ScheduledFlightViewDTO> existing = findByAircraft(reg);
        if (existing.isPartial()) {
            log.warn("Verificacao de conflitos feita com resposta parcial (peers em baixo: {})",
                    existing.unreachablePeers());
        }
        existing.items().stream()
                .filter(f -> !FlightStatus.CANCELLED.name().equals(f.status()))
                .filter(f -> Duration.between(LocalDateTime.parse(f.scheduledDateTime()), when).abs()
                        .compareTo(minGap) < 0)
                .findFirst()
                .ifPresent(f -> {
                    throw new BusinessRuleException("Aircraft " + reg + " already has flight " + f.id()
                            + " at " + f.scheduledDateTime() + ".");
                });

        ScheduledFlight saved = repository.save(new ScheduledFlight(routeId, reg, when));
        log.info("Voo {} criado LOCALMENTE (rota {}, aviao {})", saved.getId(), routeId, reg);
        return ScheduledFlightViewDTO.from(saved);
    }

    private static String normalize(String registration) {
        return registration.trim().toUpperCase();
    }
}
