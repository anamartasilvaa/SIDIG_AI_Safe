package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import org.springframework.stereotype.Service;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.*;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.NotFoundException;
import pt.isep.psoft.aisafe.airports_routes_service.domain.*;
import pt.isep.psoft.aisafe.airports_routes_service.readmodel.RouteProjector;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteHistoryRepository;
import pt.isep.psoft.aisafe.airports_routes_service.util.HalResponseUtils;
import pt.isep.psoft.aisafe.airports_routes_service.util.PartialResultContext;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RouteService {
    private static final Logger log = LoggerFactory.getLogger(RouteService.class);

    private final RouteRepository routeRepository;
    private final AirportRepository airportRepository;
    private final RouteHistoryRepository routeHistoryRepository;
    private final RouteSearchStrategy routeSearchStrategy;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ReplicationOutbox replicationOutbox;
    private final AuditService auditService;
    private final List<String> peerUrls;
    private static final String HOPS_HEADER = "X-P2P-Hops";
    private final int maxForwardingHops;

    @Value("${peer.node-id:${server.port:local}}")
    private String nodeId = "local";

    public RouteService(RouteRepository routeRepository,
                        AirportRepository airportRepository,
                        RouteHistoryRepository routeHistoryRepository,
                        RouteSearchStrategy routeSearchStrategy,
                        RestTemplate restTemplate,
                        ObjectMapper objectMapper, ReplicationOutbox replicationOutbox, AuditService auditService,
                        @Value("${peer.urls:}") List<String> peerUrls,
                        @Value("${peer.forwarding.max-hops:1}") int maxForwardingHops) {
        this.routeRepository = routeRepository;
        this.airportRepository = airportRepository;
        this.routeHistoryRepository = routeHistoryRepository;
        this.routeSearchStrategy = routeSearchStrategy;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.replicationOutbox = replicationOutbox;
        this.auditService = auditService;
        this.peerUrls = peerUrls.stream().map(String::trim).filter(url -> !url.isBlank()).toList();
        this.maxForwardingHops = maxForwardingHops;
    }

    // --- US110: Criar uma Rota ---
    @Transactional
    public RouteViewDTO createRoute(CreateRouteDTO dto, boolean isReplication) {
        return createRoute(dto, isReplication, dto.lastModified(), dto.originNodeId());
    }

    @Transactional
    public RouteViewDTO createRoute(CreateRouteDTO dto, boolean isReplication, Instant receivedAt, String receivedNode) {
        Airport origin = airportRepository.findByIataCodeString(dto.originIata())
                .orElseThrow(() -> {
                    auditService.record("CREATE", dto.routeId(), "ERROR", isReplication);
                    return new NotFoundException("Origin Airport not found: " + dto.originIata());
                });

        Airport destination = airportRepository.findByIataCodeString(dto.destinationIata())
                .orElseThrow(() -> {
                    auditService.record("CREATE", dto.routeId(), "ERROR", isReplication);
                    return new NotFoundException("Destination Airport not found: " + dto.destinationIata());
                });

        RouteId routeId = new RouteId(dto.routeId());
        if (!isReplication && routeRepository.findByRouteId(routeId).isPresent()) {
            throw new IllegalStateException("Route already exists with ID: " + dto.routeId());
        }
        if (isReplication && routeRepository.findByRouteId(routeId).isPresent()) {
            Route existing = routeRepository.findByRouteId(routeId).get();
            if (receivedAt != null && isNewer(receivedAt, receivedNode, existing.getLastModified(), existing.getOriginNodeId())) {
                existing.applyReplicationData(dto.estimatedFlightTime(), dto.minimumRange(), dto.minimumCapacity(), existing.getStatus());
                existing.applyReplicationMetadata(receivedAt, receivedNode);
                return project(routeRepository.save(existing));
            }
            return project(existing);
        }
        Route newRoute = new Route(routeId, origin, destination, dto.estimatedFlightTime(), dto.minimumRange(), dto.minimumCapacity());

        newRoute.applyReplicationMetadata(isReplication ? receivedAt : Instant.now(),
                isReplication ? receivedNode : nodeId);
        Route savedRoute = routeRepository.save(newRoute);
        // Hora do evento (lastModified, em UTC), igual em todas as instancias; nao a hora local de quem aplica.
        routeHistoryRepository.save(new RouteHistory(savedRoute.getRouteId().id(), "CREATED",
                LocalDateTime.ofInstant(savedRoute.getLastModified(), ZoneOffset.UTC)));
        if (!isReplication) {
            replicateCreate(dto, savedRoute);
        }
        auditService.record("CREATE", dto.routeId(), "SUCCESS", isReplication);
        return project(savedRoute);
    }

    // --- US112: Desativar uma rota ---
    @Transactional
    public RouteViewDTO deactivateRoute(String id, boolean isReplication) {
        return deactivateRoute(id, isReplication, null, null);
    }

    @Transactional
    public RouteViewDTO deactivateRoute(String id, boolean isReplication, Instant receivedAt, String receivedNode) {
        RouteId routeId = new RouteId(id);
        Route route = routeRepository.findByRouteId(routeId)
                .orElseThrow(() -> {
                    auditService.record("DEACTIVATE", id, "ERROR", isReplication);
                    return new NotFoundException("Route not found: " + id);
                });

        if (isReplication && receivedAt != null && !isNewer(receivedAt, receivedNode, route.getLastModified(), route.getOriginNodeId())) {
            return project(route);
        }

        if (route.getStatus() == RouteStatus.INACTIVE && isReplication) {
            return project(route);
        }

        route.deactivate();

        route.applyReplicationMetadata(isReplication ? receivedAt : Instant.now(), isReplication ? receivedNode : nodeId);
        Route saved = routeRepository.save(route);
        closeOldHistoryAndCreateNew(saved.getRouteId().id(), "DEACTIVATED",
                isReplication && receivedAt != null ? receivedAt : saved.getLastModified());

        if (!isReplication) {
            replicateDeactivate(id, saved.getLastModified(), saved.getOriginNodeId());
        }

        auditService.record("DEACTIVATE", id, "SUCCESS", isReplication);
        return project(saved);
    }

    // --- US112: Atualizar uma rota ---
    @Transactional
    public RouteViewDTO updateRoute(String id, UpdateRouteDTO dto, boolean isReplication) {
        return updateRoute(id, dto, isReplication, null, null);
    }

    @Transactional
    public RouteViewDTO updateRoute(String id, UpdateRouteDTO dto, boolean isReplication,
                                    Instant receivedAt, String receivedNode) {
        RouteId routeId = new RouteId(id);
        Route route = routeRepository.findByRouteId(routeId)
                .orElseThrow(() -> {
                    auditService.record("UPDATE", id, "ERROR", isReplication);
                    return new NotFoundException("Route not found: " + id);
                });

        if (isReplication && receivedAt != null && !isNewer(receivedAt, receivedNode, route.getLastModified(), route.getOriginNodeId())) {
            return project(route);
        }
        route.updateParameters(dto.estimatedFlightTime(), dto.minimumRange(), dto.minimumCapacity());
        route.applyReplicationMetadata(isReplication ? receivedAt : Instant.now(), isReplication ? receivedNode : nodeId);
        Route saved = routeRepository.save(route);
        closeOldHistoryAndCreateNew(saved.getRouteId().id(), "UPDATED",
                isReplication && receivedAt != null ? receivedAt : saved.getLastModified());
        if (!isReplication) {
            replicateUpdate(id, dto, saved.getLastModified(), saved.getOriginNodeId());
        }
        auditService.record("UPDATE", id, "SUCCESS", isReplication);
        return project(saved);
    }


    // --- Métodos Privados Auxiliares ---

    // --- Snapshot (instancia que arranca com a base de dados vazia) ---

    /** Todas as rotas do modelo de escrita, com lastModified e originNodeId (GET /internal/snapshot). */
    @Transactional(readOnly = true)
    public List<RouteSnapshotDTO> exportSnapshot() {
        return routeRepository.findAll().stream()
                .map(rt -> new RouteSnapshotDTO(
                        rt.getRouteId().id(),
                        rt.getOrigin().getIataCode().code(),
                        rt.getDestination().getIataCode().code(),
                        rt.getEstimatedFlightTime(), rt.getMinimumRange(), rt.getMinimumCapacity(),
                        rt.getStatus().name(), rt.getLastModified(), rt.getOriginNodeId()))
                .toList();
    }

    /**
     * Aplica uma rota recebida no snapshot de outra instancia (os aeroportos sao aplicados antes).
     * Nao gera eventos. Mesma regra do AirportService.applySnapshot (last-writer-wins; os dados iniciais
     * locais sao sempre substituidos). O historico fica so com o estado atual (CREATED e, se estiver
     * inativa, DEACTIVATED), na hora do lastModified.
     * @return true se a copia local foi criada ou atualizada.
     */
    @Transactional
    public boolean applySnapshot(RouteSnapshotDTO s) {
        RouteStatus status = RouteStatus.valueOf(s.status().toUpperCase());
        Optional<Route> existing = routeRepository.findByRouteId(new RouteId(s.routeId()));
        if (existing.isPresent()) {
            Route route = existing.get();
            if (!AirportService.snapshotWins(s.lastModified(), s.originNodeId(),
                    route.getLastModified(), route.getOriginNodeId())) {
                return false;
            }
            boolean deactivated = route.getStatus() == RouteStatus.ACTIVE && status == RouteStatus.INACTIVE;
            route.applyReplicationData(s.estimatedFlightTime(), s.minimumRange(), s.minimumCapacity(), status);
            route.applyReplicationMetadata(s.lastModified(), s.originNodeId());
            Route saved = routeRepository.save(route);
            closeOldHistoryAndCreateNew(saved.getRouteId().id(), deactivated ? "DEACTIVATED" : "UPDATED",
                    saved.getLastModified());
            project(saved);
            return true;
        }
        Airport origin = airportRepository.findByIataCodeString(s.originIata())
                .orElseThrow(() -> new NotFoundException("Origin Airport not found: " + s.originIata()));
        Airport destination = airportRepository.findByIataCodeString(s.destinationIata())
                .orElseThrow(() -> new NotFoundException("Destination Airport not found: " + s.destinationIata()));
        Route route = new Route(new RouteId(s.routeId()), origin, destination,
                s.estimatedFlightTime(), s.minimumRange(), s.minimumCapacity());
        route.applyReplicationData(s.estimatedFlightTime(), s.minimumRange(), s.minimumCapacity(), status);
        route.applyReplicationMetadata(s.lastModified(), s.originNodeId());
        Route saved = routeRepository.save(route);
        routeHistoryRepository.save(new RouteHistory(saved.getRouteId().id(), "CREATED",
                LocalDateTime.ofInstant(saved.getLastModified(), ZoneOffset.UTC)));
        if (status == RouteStatus.INACTIVE) {
            closeOldHistoryAndCreateNew(saved.getRouteId().id(), "DEACTIVATED", saved.getLastModified());
        }
        project(saved);
        return true;
    }

    /** CQRS: modelo de leitura atualizado pelo projetor (opcional para permitir testes unitarios). */
    @Autowired(required = false)
    private RouteProjector routeProjector;

    /** Mapeia o agregado e atualiza o modelo de leitura na mesma transacao do comando. */
    private RouteViewDTO project(Route route) {
        RouteViewDTO dto = convertToDTO(route);
        if (routeProjector != null) {
            routeProjector.project(route);
        }
        return dto;
    }

    /** Reconstroi todo o modelo de leitura a partir do modelo de escrita. */
    @Transactional
    public int rebuildReadModel() {
        if (routeProjector == null) return 0;
        List<Route> all = routeRepository.findAll();
        all.forEach(routeProjector::project);
        return all.size();
    }

    private RouteViewDTO convertToDTO(Route route) {
        return new RouteViewDTO(
                route.getRouteId().id(),
                route.getOrigin().getIataCode().code(),
                route.getDestination().getIataCode().code(),
                route.getStatus().name(),
                route.getMinimumCapacity(),
                route.getLastModified(),
                route.getOriginNodeId(),
                route.getMinimumRange(),
                route.getEstimatedFlightTime()
        );
    }

    private void replicateCreate(CreateRouteDTO dto, Route route) {
        CreateRouteDTO payload = "local".equals(nodeId) ? dto : new CreateRouteDTO(dto.routeId(), dto.originIata(), dto.destinationIata(),
                dto.estimatedFlightTime(), dto.minimumRange(), dto.minimumCapacity(),
                route.getLastModified(), route.getOriginNodeId());
        replicationOutbox.enqueue(peerUrls, "POST", "/api/routes", payload);
    }

    private void replicateUpdate(String id, UpdateRouteDTO dto, Instant lastModified, String originNodeId) {
        replicatePatch(id, dto, lastModified, originNodeId);
    }

    private void replicateDeactivate(String id, Instant lastModified, String originNodeId) {
        replicatePatch(id + "/deactivate", "", lastModified, originNodeId);
    }

    private void replicatePatch(String path, Object body, Instant lastModified, String originNodeId) {
        replicationOutbox.enqueue(peerUrls, "PATCH", "/api/routes/" + path, body, lastModified, originNodeId);
    }

    private boolean isNewer(Instant incoming, String incomingNode, Instant current, String currentNode) {
        if (incoming == null) return false;
        if (current == null) return true;
        int c = incoming.compareTo(current);
        return c > 0 || (c == 0 && String.valueOf(incomingNode).compareTo(String.valueOf(currentNode)) > 0);
    }

    private <T> HttpEntity<T> p2pEntity(int hops) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HOPS_HEADER, String.valueOf(hops));
        return new HttpEntity<>(headers);
    }

    private void closeOldHistoryAndCreateNew(String routeId, String action, Instant eventTimestamp) {
        LocalDateTime eventDateTime = LocalDateTime.ofInstant(eventTimestamp, ZoneOffset.UTC);
        Optional<RouteHistory> activeHistory = routeHistoryRepository.findFirstByRouteIdAndEndDateIsNull(routeId);
        if (activeHistory.isPresent()) {
            RouteHistory old = activeHistory.get();
            // Protecao para eventos fora de ordem ou relogios desalinhados: o fim nunca fica antes do inicio.
            if (!eventDateTime.isAfter(old.getStartDate())) {
                eventDateTime = old.getStartDate().plusNanos(1_000);
            }
            old.closeHistory(eventDateTime);
            routeHistoryRepository.save(old);
        }
        routeHistoryRepository.save(new RouteHistory(routeId, action, eventDateTime));
    }
}