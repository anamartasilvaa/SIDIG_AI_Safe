package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.*;
import pt.isep.psoft.aisafe.airports_routes_service.domain.*;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteHistoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RouteService {
    private static final Logger log = LoggerFactory.getLogger(RouteService.class);

    private final RouteRepository routeRepository;
    private final AirportRepository airportRepository;
    private final RouteHistoryRepository routeHistoryRepository;
    private final RouteSearchStrategy routeSearchStrategy; // <-- A Estratégia adicionada aqui
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String peerUrl;

    public RouteService(RouteRepository routeRepository,
                        AirportRepository airportRepository,
                        RouteHistoryRepository routeHistoryRepository,
                        RouteSearchStrategy routeSearchStrategy,
                        RestTemplate restTemplate,
                        ObjectMapper objectMapper,
                        @Value("${peer.url:http://localhost:8092}") String peerUrl) { // <-- E aqui no construtor
        this.routeRepository = routeRepository;
        this.airportRepository = airportRepository;
        this.routeHistoryRepository = routeHistoryRepository;
        this.routeSearchStrategy = routeSearchStrategy;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.peerUrl = peerUrl;
    }

    // --- US110: Criar uma Rota ---
    public RouteViewDTO createRoute(CreateRouteDTO dto, boolean isReplication) {
        Airport origin = airportRepository.findByIataCodeString(dto.originIata())
                .orElseThrow(() -> new IllegalArgumentException("Origin Airport not found: " + dto.originIata()));

        Airport destination = airportRepository.findByIataCodeString(dto.destinationIata())
                .orElseThrow(() -> new IllegalArgumentException("Destination Airport not found: " + dto.destinationIata()));

        RouteId routeId = new RouteId(dto.routeId());
        Route newRoute = new Route(routeId, origin, destination, dto.estimatedFlightTime(), dto.minimumRange(), dto.minimumCapacity());

        Route savedRoute = routeRepository.save(newRoute);
        routeHistoryRepository.save(new RouteHistory(savedRoute.getRouteId().id(), "CREATED"));
        if (!isReplication) {
            replicateCreate(dto);
        }

        return convertToDTO(savedRoute);
    }

    // --- US112: Desativar uma rota ---
    public RouteViewDTO deactivateRoute(String id, boolean isReplication) {
        RouteId routeId = new RouteId(id);
        Route route = routeRepository.findByRouteId(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + id));

        route.deactivate();
        Route saved = routeRepository.save(route);
        closeOldHistoryAndCreateNew(saved.getRouteId().id(), "DEACTIVATED");
        if (!isReplication) {
            replicateDeactivate(id);
        }

        return convertToDTO(saved);
    }

    // --- US112: Atualizar uma rota ---
    public RouteViewDTO updateRoute(String id, UpdateRouteDTO dto, boolean isReplication) {
        RouteId routeId = new RouteId(id);
        Route route = routeRepository.findByRouteId(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + id));

        route.updateParameters(dto.estimatedFlightTime(), dto.minimumRange(), dto.minimumCapacity());
        Route saved = routeRepository.save(route);
        closeOldHistoryAndCreateNew(saved.getRouteId().id(), "UPDATED");
        if (!isReplication) {
            replicateUpdate(id, dto);
        }

        return convertToDTO(saved);
    }

    // --- US113: Ver Detalhes de uma Rota Específica ---
    public RouteViewDTO getRoute(String id) {
        RouteId routeId = new RouteId(id);
        Optional<Route> localRoute = routeRepository.findByRouteId(routeId);
        if (localRoute.isPresent()) {
            return convertToDTO(localRoute.get());
        }

        String url = UriComponentsBuilder.fromUriString(peerUrl)
                .pathSegment("api", "routes", id)
                .toUriString();
        try {
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null || body.isNull()) {
                throw routeNotFound(id);
            }

            JsonNode dtoNode = body.has("content") ? body.get("content") : body;
            return objectMapper.treeToValue(dtoNode, RouteViewDTO.class);
        } catch (HttpClientErrorException.NotFound exception) {
            throw routeNotFound(id);
        } catch (HttpClientErrorException exception) {
            throw new IllegalStateException("Peer route lookup failed with status "
                    + exception.getStatusCode(), exception);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid route response from peer", exception);
        }
    }

    // --- US113 e US114: Pesquisar Rotas  ---
    public Page<RouteViewDTO> searchRoutes(String origin, String destination, Pageable pageable) {
        Page<Route> results;

        if (origin != null && destination != null) {
            results = routeRepository.findByOrigin_IataCode_CodeAndDestination_IataCode_Code(origin, destination, pageable);
        } else if (origin != null) {
            results = routeRepository.findByOrigin_IataCode_Code(origin, pageable);
        } else if (destination != null) {
            results = routeRepository.findByDestination_IataCode_Code(destination, pageable);
        } else {
            results = routeRepository.findAll(pageable);
        }

        if (!results.isEmpty()) {
            return results.map(this::convertToDTO);
        }

        return searchRoutesOnPeer(origin, destination, pageable);
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

    //US209
    public Page<RouteViewDTO> getRoutesByAirport(String iataCode, Pageable pageable) {
        String code = iataCode.toUpperCase();
        Page<Route> routes = routeRepository.findRoutesByAirport(code, pageable);
        if (routes.isEmpty()) {
            return searchRoutesOnPeer(code, pageable);
        }
        return routes.map(this::convertToDTO);
    }

    private Page<RouteViewDTO> searchRoutesOnPeer(String iataCode, Pageable pageable) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(peerUrl)
                .pathSegment("api", "routes", "involving", iataCode)
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());
        return fetchPeerRoutes(builder, iataCode, pageable);
    }

    private Page<RouteViewDTO> searchRoutesOnPeer(String origin, String destination, Pageable pageable) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(peerUrl)
                .path("api/routes")
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());
        if (origin != null) {
            builder.queryParam("origin", origin);
        }
        if (destination != null) {
            builder.queryParam("destination", destination);
        }

        return fetchPeerRoutes(builder, origin != null ? origin : destination, pageable);
    }

    private Page<RouteViewDTO> fetchPeerRoutes(UriComponentsBuilder builder,
                                               String routeIdentifier,
                                               Pageable pageable) {
        try {
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(builder.toUriString(), JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null || body.isNull()) {
                return Page.empty(pageable);
            }

            JsonNode embedded = body.path("_embedded");
            JsonNode routesNode = embedded.path("routeViewDTO");
            if (!routesNode.isArray()) {
                routesNode = embedded.path("routes");
            }

            List<RouteViewDTO> routes = new ArrayList<>();
            if (routesNode.isArray()) {
                for (JsonNode routeNode : routesNode) {
                    routes.add(objectMapper.treeToValue(routeNode, RouteViewDTO.class));
                }
            }

            long totalElements = body.path("page").path("totalElements").asLong(routes.size());
            return new PageImpl<>(routes, pageable, totalElements);
        } catch (HttpClientErrorException.NotFound exception) {
            throw routeNotFound(routeIdentifier);
        } catch (HttpClientErrorException exception) {
            throw new IllegalStateException("Peer route search failed with status "
                    + exception.getStatusCode(), exception);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid route search response from peer", exception);
        }
    }

    private IllegalArgumentException routeNotFound(String id) {
        return new IllegalArgumentException("Route not found: " + id);
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
        Page<Route> routes;

        // Decide a ordenação baseada no parâmetro recebido
        if ("popularity".equalsIgnoreCase(sortBy)) {
            routes = routeRepository.findAllActiveRoutesSortedByPopularity(pageable);
        } else {
            routes = routeRepository.findAllActiveRoutesSortedByDistance(pageable);
        }

        // Usa o teu próprio método convertToDTO em vez de criar um DTO novo à mão!
        return routes.map(this::convertToDTO);
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
                route.getMinimumCapacity()
        );
    }

    private void replicateCreate(CreateRouteDTO dto) {
        String url = UriComponentsBuilder.fromUriString(peerUrl).pathSegment("api", "routes").toUriString();
        try {
            restTemplate.postForEntity(url, replicationEntity(dto), Void.class);
        } catch (ResourceAccessException exception) {
            log.warn("Falha ao replicar para o peer", exception);
        }
    }

    private void replicateUpdate(String id, UpdateRouteDTO dto) {
        replicatePatch(id, dto);
    }

    private void replicateDeactivate(String id) {
        replicatePatch(id + "/deactivate", null);
    }

    private void replicatePatch(String path, Object body) {
        String url = UriComponentsBuilder.fromUriString(peerUrl)
                .pathSegment("api", "routes")
                .path("/" + path)
                .toUriString();
        try {
            restTemplate.patchForObject(url, replicationEntity(body), Void.class);
        } catch (ResourceAccessException exception) {
            log.warn("Falha ao replicar para o peer", exception);
        }
    }

    private <T> HttpEntity<T> replicationEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-P2P-Sync", "true");
        return new HttpEntity<>(body, headers);
    }

    private void closeOldHistoryAndCreateNew(String routeId, String action) {
        Optional<RouteHistory> activeHistory = routeHistoryRepository.findFirstByRouteIdAndEndDateIsNull(routeId);
        if (activeHistory.isPresent()) {
            RouteHistory old = activeHistory.get();
            old.closeHistory(LocalDateTime.now());
            routeHistoryRepository.save(old);
        }
        routeHistoryRepository.save(new RouteHistory(routeId, action));
    }

    // US228
    public String exportRoutesAsGeoJson() {
        List<Route> allRoutes = routeRepository.findAll();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"type\": \"FeatureCollection\",\n");
        sb.append("  \"features\": [\n");

        boolean isFirst = true;

        for (Route route : allRoutes) {
            if (!isFirst) {
                sb.append(",\n");
            }
            isFirst = false;

            Airport origin = route.getOrigin();
            Airport dest = route.getDestination();

            sb.append("    {\n");
            sb.append("      \"type\": \"Feature\",\n");
            sb.append("      \"properties\": {\n");
            sb.append("        \"routeId\": \"").append(route.getRouteId().id()).append("\",\n");
            sb.append("        \"origin\": \"").append(origin.getIataCode().code()).append("\",\n");
            sb.append("        \"destination\": \"").append(dest.getIataCode().code()).append("\",\n");
            sb.append("        \"status\": \"").append(route.getStatus().name()).append("\"\n");
            sb.append("      },\n");
            sb.append("      \"geometry\": {\n");
            sb.append("        \"type\": \"LineString\",\n");
            sb.append("        \"coordinates\": [\n");
            sb.append("          [").append(origin.getCoordinates().longitude()).append(", ").append(origin.getCoordinates().latitude()).append("],\n");
            sb.append("          [").append(dest.getCoordinates().longitude()).append(", ").append(dest.getCoordinates().latitude()).append("]\n");
            sb.append("        ]\n");
            sb.append("      }\n");
            sb.append("    }");
        }

        sb.append("\n  ]\n");
        sb.append("}\n");

        return sb.toString();
    }
}