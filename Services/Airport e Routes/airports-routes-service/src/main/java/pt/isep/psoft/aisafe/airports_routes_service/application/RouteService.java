package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import org.springframework.stereotype.Service;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.*;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.NotFoundException;
import pt.isep.psoft.aisafe.airports_routes_service.domain.*;
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
                return convertToDTO(routeRepository.save(existing));
            }
            return convertToDTO(existing);
        }
        Route newRoute = new Route(routeId, origin, destination, dto.estimatedFlightTime(), dto.minimumRange(), dto.minimumCapacity());

        newRoute.applyReplicationMetadata(isReplication ? receivedAt : Instant.now(),
                isReplication ? receivedNode : nodeId);
        Route savedRoute = routeRepository.save(newRoute);
        routeHistoryRepository.save(new RouteHistory(savedRoute.getRouteId().id(), "CREATED"));
        if (!isReplication) {
            replicateCreate(dto, savedRoute);
        }
        auditService.record("CREATE", dto.routeId(), "SUCCESS", isReplication);
        return convertToDTO(savedRoute);
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
            return convertToDTO(route);
        }

        if (route.getStatus() == RouteStatus.INACTIVE && isReplication) {
            return convertToDTO(route);
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
        return convertToDTO(saved);
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
            return convertToDTO(route);
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
        return convertToDTO(saved);
    }

    // --- US113: Ver Detalhes de uma Rota Específica ---
    public RouteViewDTO getRoute(String id) {
        return getRoute(id, 0);
    }

    public RouteViewDTO getRoute(String id, int hops) {
        hops = Math.max(0, hops);
        RouteId routeId = new RouteId(id);
        Optional<Route> localRoute = routeRepository.findByRouteId(routeId);
        if (localRoute.isPresent()) {
            return convertToDTO(localRoute.get());
        }

        if (hops >= maxForwardingHops) {
            throw routeNotFound(id);
        }

        boolean peerFailure = false;
        for (String peerUrl : peerUrls) {
            String url = UriComponentsBuilder.fromUriString(peerUrl)
                    .pathSegment("api", "routes", id).toUriString();
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, p2pEntity(hops + 1), String.class);
                if (response.getBody() == null || response.getBody().isBlank()) {
                    continue;
                }
                JsonNode body = objectMapper.readTree(response.getBody());
                JsonNode dtoNode = body.has("content") ? body.get("content") : body;
                return objectMapper.treeToValue(dtoNode, RouteViewDTO.class);
            } catch (HttpClientErrorException.NotFound exception) {
                log.debug("Peer route lookup returned 404 for {}", peerUrl);
            } catch (Exception exception) {
                peerFailure = true;
                log.warn("Peer route lookup failed for {}", peerUrl, exception);
            }
        }
        if (peerFailure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "One or more peers were unavailable");
        }
        throw routeNotFound(id);
    }

    // --- US113 e US114: Pesquisar Rotas ---
    public Page<RouteViewDTO> searchRoutes(String origin, String destination, Pageable pageable) {
        return searchRoutes(origin, destination, pageable, 0);
    }

    public Page<RouteViewDTO> searchRoutes(String origin, String destination, Pageable pageable, int hops) {
        hops = Math.max(0, hops);
        PartialResultContext.start();
        List<RouteViewDTO> routes = new ArrayList<>();

        if (origin != null && destination != null) {
            routeRepository.findByOrigin_IataCode_CodeAndDestination_IataCode_Code(origin, destination, Pageable.unpaged())
                    .map(this::convertToDTO).forEach(routes::add);
        } else if (origin != null) {
            routeRepository.findByOrigin_IataCode_Code(origin, Pageable.unpaged())
                    .map(this::convertToDTO).forEach(routes::add);
        } else if (destination != null) {
            routeRepository.findByDestination_IataCode_Code(destination, Pageable.unpaged())
                    .map(this::convertToDTO).forEach(routes::add);
        } else {
            routeRepository.findAll(Pageable.unpaged()).map(this::convertToDTO).forEach(routes::add);
        }

        if (hops < maxForwardingHops) {
            routes.addAll(searchRoutesOnPeers(origin, destination, pageable, hops));
        }
        return pageRoutes(deduplicateRoutes(routes), pageable);
    }

    // --- US111: Ver Histórico da Rota ---
    public List<RouteHistoryDTO> getRouteHistory(String routeId) {
        List<RouteHistory> entities = routeHistoryRepository.findByRouteIdOrderByStartDateDesc(routeId);
        return entities.stream().map(entity ->
                new RouteHistoryDTO(
                        entity.getRouteId(),
                        entity.getAction(),
                        entity.getStartDate(),
                        entity.getEndDate()
                )
        ).collect(Collectors.toList());
    }

    // US209
    public Page<RouteViewDTO> getRoutesByAirport(String iataCode, Pageable pageable) {
        return getRoutesByAirport(iataCode, pageable, 0);
    }

    public Page<RouteViewDTO> getRoutesByAirport(String iataCode, Pageable pageable, int hops) {
        hops = Math.max(0, hops);
        PartialResultContext.start();
        String code = iataCode.toUpperCase();
        List<RouteViewDTO> routes = new ArrayList<>(routeRepository.findRoutesByAirport(code, Pageable.unpaged())
                .map(this::convertToDTO).getContent());
        if (hops < maxForwardingHops) {
            routes.addAll(searchRoutesOnPeers(code, pageable, hops));
        }
        return pageRoutes(deduplicateRoutes(routes), pageable);
    }

    private List<RouteViewDTO> searchRoutesOnPeers(String iataCode, Pageable pageable, int hops) {
        List<RouteViewDTO> result = new ArrayList<>();
        for (String peerUrl : peerUrls) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(peerUrl)
                    .pathSegment("api", "routes", "involving", iataCode);
            result.addAll(fetchPeerRoutes(builder, pageable, hops));
        }
        return result;
    }

    private List<RouteViewDTO> searchRoutesOnPeers(String origin, String destination, Pageable pageable, int hops) {
        List<RouteViewDTO> result = new ArrayList<>();
        for (String peerUrl : peerUrls) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(peerUrl)
                    .path("api/routes");
            if (origin != null) builder.queryParam("origin", origin);
            if (destination != null) builder.queryParam("destination", destination);
            result.addAll(fetchPeerRoutes(builder, pageable, hops));
        }
        return result;
    }

    private List<RouteViewDTO> fetchPeerRoutes(UriComponentsBuilder builder, Pageable pageable, int hops) {
        if (hops >= maxForwardingHops) {
            return List.of();
        }
        try {
            List<RouteViewDTO> routes = new ArrayList<>();
            int page = 0;
            int totalPages = 1;
            do {
                UriComponentsBuilder pageBuilder = builder.cloneBuilder()
                        .replaceQueryParam("page", page)
                        .replaceQueryParam("size", 2000);
                ResponseEntity<String> response = restTemplate.exchange(
                        pageBuilder.toUriString(), HttpMethod.GET, p2pEntity(hops + 1), String.class);
                if (response.getBody() == null || response.getBody().isBlank()) break;
                JsonNode body = objectMapper.readTree(response.getBody());
                JsonNode routesNode = HalResponseUtils.firstEmbeddedArray(body);
                if (routesNode != null) {
                    for (JsonNode routeNode : routesNode) {
                        routes.add(objectMapper.treeToValue(routeNode, RouteViewDTO.class));
                    }
                }
                totalPages = Math.max(1, body.path("page").path("totalPages").asInt(1));
                page++;
            } while (page < totalPages);
            return routes;
        } catch (HttpClientErrorException.NotFound exception) {
            return List.of();
        } catch (Exception exception) {
            PartialResultContext.markPartial();
            log.warn("Peer route search failed for {}", builder, exception);
            return List.of();
        }
    }

    private List<RouteViewDTO> deduplicateRoutes(List<RouteViewDTO> routes) {
        Map<String, RouteViewDTO> unique = new LinkedHashMap<>();
        routes.forEach(route -> unique.merge(route.routeId(), route,
                this::newerRouteVersion));
        return new ArrayList<>(unique.values());
    }

    private RouteViewDTO newerRouteVersion(RouteViewDTO current, RouteViewDTO candidate) {
        if (current.lastModified() == null) return candidate;
        if (candidate.lastModified() == null) return current;
        int timestampComparison = candidate.lastModified().compareTo(current.lastModified());
        if (timestampComparison != 0) return timestampComparison > 0 ? candidate : current;
        String currentNode = current.originNodeId() == null ? "" : current.originNodeId();
        String candidateNode = candidate.originNodeId() == null ? "" : candidate.originNodeId();
        return candidateNode.compareTo(currentNode) > 0 ? candidate : current;
    }

    private Page<RouteViewDTO> pageRoutes(List<RouteViewDTO> routes, Pageable pageable) {
        if (pageable.getSort().isSorted()) {
            Comparator<RouteViewDTO> comparator = null;
            for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
                Comparator<RouteViewDTO> field = Comparator.comparing(
                        route -> routeSortValue(route, order.getProperty()),
                        Comparator.nullsFirst(String::compareTo));
                if (order.isDescending()) field = field.reversed();
                comparator = comparator == null ? field : comparator.thenComparing(field);
            }
            if (comparator != null) routes.sort(comparator);
        }
        if (pageable.isUnpaged()) return new PageImpl<>(routes);
        int from = (int) Math.min((long) pageable.getOffset(), routes.size());
        int to = Math.min(from + pageable.getPageSize(), routes.size());
        return new PageImpl<>(routes.subList(from, to), pageable, routes.size());
    }

    private String routeSortValue(RouteViewDTO route, String property) {
        return switch (property) {
            case "origin", "originIata" -> route.originIata();
            case "destination", "destinationIata" -> route.destinationIata();
            case "status" -> route.status();
            case "minimumCapacity", "capacity" -> String.valueOf(route.minimumCapacity());
            default -> route.routeId();
        };
    }

    private NotFoundException routeNotFound(String id) {
        return new NotFoundException("Route not found: " + id);
    }

    // US210
    public List<pt.isep.psoft.aisafe.airports_routes_service.application.DTO.BusiestAirportDTO> getBusiestAirports() {
        List<Airport> airports = airportRepository.findAll();

        return airports.stream()
                .map(airport -> {
                    String iata = airport.getIataCode().code();
                    long count = routeRepository.countRoutesForAirport(iata);
                    return new pt.isep.psoft.aisafe.airports_routes_service.application.DTO.BusiestAirportDTO(iata, airport.getName(), count);
                })
                .sorted((a1, a2) -> Long.compare(a2.routeCount(), a1.routeCount()))
                .collect(Collectors.toList());
    }

    // --- US215: Distância total da rede ---
    public Double getTotalNetworkDistance() {
        Double total = routeRepository.calculateTotalNetworkDistance();
        return (total != null) ? total : 0.0;
    }

    // --- US214: Listar rotas ativas ordenadas por popularidade ou distância ---
    public Page<RouteViewDTO> getActiveRoutesSorted(String sortBy, Pageable pageable) {
        return getActiveRoutesSorted(sortBy, pageable, 0);
    }

    public Page<RouteViewDTO> getActiveRoutesSorted(String sortBy, Pageable pageable, int hops) {
        hops = Math.max(0, hops);
        PartialResultContext.start();
        Page<Route> routes;

        if ("popularity".equalsIgnoreCase(sortBy)) {
            routes = routeRepository.findAllActiveRoutesSortedByPopularity(Pageable.unpaged());
        } else {
            routes = routeRepository.findAllActiveRoutesSortedByDistance(Pageable.unpaged());
        }

        List<RouteViewDTO> allRoutes = new ArrayList<>(routes.map(this::convertToDTO).getContent());
        if (hops < maxForwardingHops) {
            for (String peerUrl : peerUrls) {
                UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(peerUrl)
                        .pathSegment("api", "routes", "active", "sorted")
                        .queryParam("sortBy", sortBy);
                allRoutes.addAll(fetchPeerRoutes(builder, pageable, hops));
            }
        }
        return pageRoutes(deduplicateRoutes(allRoutes), pageable);
    }

    // --- US216: Procurar rotas alternativas ---
    public List<AlternativeRouteDTO> findAlternativeRoutes(String originIata, String destinationIata) {
        return routeSearchStrategy.findAlternatives(originIata, destinationIata);
    }

    // --- Métodos Privados Auxiliares ---

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
            old.closeHistory(eventDateTime);
            routeHistoryRepository.save(old);
        }
        routeHistoryRepository.save(new RouteHistory(routeId, action, eventDateTime));
    }

    // US228
    public String exportRoutesAsGeoJson() {
        List<Route> allRoutes = routeRepository.findAll();
        List<GeoJsonFeature> features = allRoutes.stream()
                .map(this::toGeoJsonFeature)
                .toList();

        try {
            return objectMapper.writeValueAsString(new GeoJsonFeatureCollection(features));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize routes as GeoJSON", exception);
        }
    }

    private GeoJsonFeature toGeoJsonFeature(Route route) {
        Airport origin = route.getOrigin();
        Airport destination = route.getDestination();
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("routeId", route.getRouteId().id());
        properties.put("origin", origin.getIataCode().code());
        properties.put("destination", destination.getIataCode().code());
        properties.put("status", route.getStatus().name());

        return new GeoJsonFeature(
                properties,
                new GeoJsonLineString(List.of(
                        List.of(origin.getCoordinates().longitude(), origin.getCoordinates().latitude()),
                        List.of(destination.getCoordinates().longitude(), destination.getCoordinates().latitude())
                ))
        );
    }

    private record GeoJsonFeatureCollection(
            String type,
            List<GeoJsonFeature> features
    ) {
        private GeoJsonFeatureCollection(List<GeoJsonFeature> features) {
            this("FeatureCollection", features);
        }
    }

    private record GeoJsonFeature(
            String type,
            Map<String, String> properties,
            GeoJsonLineString geometry
    ) {
        private GeoJsonFeature(Map<String, String> properties, GeoJsonLineString geometry) {
            this("Feature", properties, geometry);
        }
    }

    private record GeoJsonLineString(
            String type,
            List<List<Double>> coordinates
    ) {
        private GeoJsonLineString(List<List<Double>> coordinates) {
            this("LineString", coordinates);
        }
    }
}