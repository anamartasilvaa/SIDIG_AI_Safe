package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.NotFoundException;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.*;
import pt.isep.psoft.aisafe.airports_routes_service.domain.RouteHistory;
import pt.isep.psoft.aisafe.airports_routes_service.readmodel.AirportReadRepository;
import pt.isep.psoft.aisafe.airports_routes_service.readmodel.RouteReadModel;
import pt.isep.psoft.aisafe.airports_routes_service.readmodel.RouteReadRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteHistoryRepository;
import pt.isep.psoft.aisafe.airports_routes_service.util.HalResponseUtils;
import pt.isep.psoft.aisafe.airports_routes_service.util.PartialResultContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CQRS, lado das QUERIES (leitura) das rotas.
 *
 * Le exclusivamente do modelo de leitura (route_read_model e airport_read_model), mantido pelos
 * projetores a partir dos comandos e dos eventos recebidos do broker. Nao usa o modelo de escrita
 * (RouteRepository / entidade Route). Mantem o encaminhamento P2P das leituras (X-P2P-Hops).
 */
@Service
public class RouteQueryService {
    private static final Logger log = LoggerFactory.getLogger(RouteQueryService.class);
    private static final String HOPS_HEADER = "X-P2P-Hops";

    private final RouteReadRepository routeReadRepository;
    private final AirportReadRepository airportReadRepository;
    private final RouteHistoryRepository routeHistoryRepository;
    private final RouteSearchStrategy routeSearchStrategy;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final List<String> peerUrls;
    private final int maxForwardingHops;

    public RouteQueryService(RouteReadRepository routeReadRepository,
                             AirportReadRepository airportReadRepository,
                             RouteHistoryRepository routeHistoryRepository,
                             RouteSearchStrategy routeSearchStrategy,
                             RestTemplate restTemplate,
                             ObjectMapper objectMapper,
                             @Value("${peer.urls:}") List<String> peerUrls,
                             @Value("${peer.forwarding.max-hops:1}") int maxForwardingHops) {
        this.routeReadRepository = routeReadRepository;
        this.airportReadRepository = airportReadRepository;
        this.routeHistoryRepository = routeHistoryRepository;
        this.routeSearchStrategy = routeSearchStrategy;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.peerUrls = peerUrls.stream().map(String::trim).filter(url -> !url.isBlank()).toList();
        this.maxForwardingHops = maxForwardingHops;
    }

    private RouteViewDTO toView(RouteReadModel route) {
        return new RouteViewDTO(
                route.getRouteId(),
                route.getOriginIata(),
                route.getDestinationIata(),
                route.getStatus(),
                route.getMinimumCapacity(),
                route.getLastModified(),
                route.getOriginNodeId(),
                route.getMinimumRange(),
                route.getEstimatedFlightTime());
    }

    // --- US113: Ver Detalhes de uma Rota Específica ---
    public RouteViewDTO getRoute(String id) {
        return getRoute(id, 0);
    }

    public RouteViewDTO getRoute(String id, int hops) {
        hops = Math.max(0, hops);
        Optional<RouteReadModel> localRoute = routeReadRepository.findById(id);
        if (localRoute.isPresent()) {
            return toView(localRoute.get());
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
            routeReadRepository.findByOriginIataAndDestinationIata(origin, destination)
                    .stream().map(this::toView).forEach(routes::add);
        } else if (origin != null) {
            routeReadRepository.findByOriginIata(origin)
                    .stream().map(this::toView).forEach(routes::add);
        } else if (destination != null) {
            routeReadRepository.findByDestinationIata(destination)
                    .stream().map(this::toView).forEach(routes::add);
        } else {
            routeReadRepository.findAll().stream().map(this::toView).forEach(routes::add);
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
        List<RouteViewDTO> routes = new ArrayList<>(routeReadRepository.findByOriginIataOrDestinationIata(code, code)
                .stream().map(this::toView).toList());
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
        return airportReadRepository.findAll().stream()
                .map(airport -> {
                    String iata = airport.getIataCode();
                    long count = routeReadRepository.countByOriginIataOrDestinationIata(iata, iata);
                    return new pt.isep.psoft.aisafe.airports_routes_service.application.DTO.BusiestAirportDTO(iata, airport.getName(), count);
                })
                .sorted((a1, a2) -> Long.compare(a2.routeCount(), a1.routeCount()))
                .collect(Collectors.toList());
    }

    // --- US215: Distância total da rede ---
    public Double getTotalNetworkDistance() {
        Double total = routeReadRepository.calculateTotalNetworkDistance();
        return (total != null) ? total : 0.0;
    }

    // --- US214: Listar rotas ativas ordenadas por popularidade ou distância ---
    public Page<RouteViewDTO> getActiveRoutesSorted(String sortBy, Pageable pageable) {
        return getActiveRoutesSorted(sortBy, pageable, 0);
    }

    public Page<RouteViewDTO> getActiveRoutesSorted(String sortBy, Pageable pageable, int hops) {
        hops = Math.max(0, hops);
        PartialResultContext.start();
        // A popularidade pertence a outro contexto: tal como antes, usa-se a distancia (minimumRange).
        List<RouteViewDTO> allRoutes = new ArrayList<>(routeReadRepository
                .findByStatusOrderByMinimumRangeAsc("ACTIVE").stream().map(this::toView).toList());
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

    private <T> HttpEntity<T> p2pEntity(int hops) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HOPS_HEADER, String.valueOf(hops));
        return new HttpEntity<>(headers);
    }

    // US228
    public String exportRoutesAsGeoJson() {
        List<RouteReadModel> allRoutes = routeReadRepository.findAll();
        List<GeoJsonFeature> features = allRoutes.stream()
                .map(this::toGeoJsonFeature)
                .toList();

        try {
            return objectMapper.writeValueAsString(new GeoJsonFeatureCollection(features));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize routes as GeoJSON", exception);
        }
    }

    private GeoJsonFeature toGeoJsonFeature(RouteReadModel route) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("routeId", route.getRouteId());
        properties.put("origin", route.getOriginIata());
        properties.put("destination", route.getDestinationIata());
        properties.put("status", route.getStatus());

        return new GeoJsonFeature(
                properties,
                new GeoJsonLineString(List.of(
                        List.of(route.getOriginLongitude(), route.getOriginLatitude()),
                        List.of(route.getDestinationLongitude(), route.getDestinationLatitude())
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
