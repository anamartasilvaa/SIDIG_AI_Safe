package pt.isep.psoft.aisafe.airports_routes_service.application;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AlternativeRouteDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RouteViewDTO;
import pt.isep.psoft.aisafe.airports_routes_service.domain.Route;
import pt.isep.psoft.aisafe.airports_routes_service.domain.RouteStatus;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteRepository;
import pt.isep.psoft.aisafe.airports_routes_service.util.HalResponseUtils;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class FastestRouteStrategy implements RouteSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(FastestRouteStrategy.class);
    private final RouteRepository routeRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final List<String> peerUrls;
    private static final int MINIMUM_CONNECTING_TIME_MINUTES = 60; // Regra do MCT (Minimum Connection Time)
    private static final int MAX_FLIGHTS = 3; // Limite fixo: máximo de 3 voos (2 escalas).

    public FastestRouteStrategy(RouteRepository routeRepository, RestTemplate restTemplate,
                                ObjectMapper objectMapper,
                                @Value("${peer.urls:}") List<String> peerUrls) {
        this.routeRepository = routeRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.peerUrls = peerUrls.stream().map(String::trim).filter(url -> !url.isBlank()).toList();
    }

    @Override
    public List<AlternativeRouteDTO> findAlternatives(String originIata, String destinationIata) {
        List<AlternativeRouteDTO> foundAlternatives = new ArrayList<>();
        Queue<List<RouteViewDTO>> queue = new LinkedList<>();

        log.info("Starting search for fastest alternative routes from {} to {}", originIata, destinationIata);

        // 1. Procurar os voos iniciais (sem escalas)
        List<RouteViewDTO> initialRoutes = getOutgoingRoutes(originIata);
        for (RouteViewDTO route : initialRoutes) {
            List<RouteViewDTO> initialPath = new ArrayList<>();
            initialPath.add(route);
            queue.add(initialPath);
        }

        // 2. Procurar ligações usando Breadth-First Search (BFS)
        while (!queue.isEmpty()) {
            List<RouteViewDTO> currentPath = queue.poll();
            RouteViewDTO lastRoute = currentPath.get(currentPath.size() - 1);
            String currentAirport = lastRoute.destinationIata();

            // Se chegámos ao destino, processar a rota e guardar
            if (currentAirport.equalsIgnoreCase(destinationIata)) {
                AlternativeRouteDTO dto = convertToAlternativeDTO(currentPath);
                foundAlternatives.add(dto);

                // Imprime a árvore de decisão na consola
                String pathString = currentPath.stream()
                        .map(RouteViewDTO::originIata)
                        .collect(Collectors.joining(" -> ")) + " -> " + destinationIata;
                log.debug("Found valid path: {} | Total cost: {} minutes (includes MCT penalties)",
                        pathString, dto.totalEstimatedFlightTime());
                continue;
            }

            // O limite mágico para evitar loops infinitos
            if (currentPath.size() >= MAX_FLIGHTS) {
                continue;
            }

            // Expandir a pesquisa a partir deste aeroporto
            List<RouteViewDTO> nextRoutes = getOutgoingRoutes(currentAirport);
            for (RouteViewDTO nextRoute : nextRoutes) {
                if (!isAirportInPath(currentPath, nextRoute.destinationIata())) {
                    List<RouteViewDTO> newPath = new ArrayList<>(currentPath);
                    newPath.add(nextRoute);
                    queue.add(newPath);
                }
            }
        }

        // 3. Ordenar os resultados do Mais Rápido para o Mais Lento
        foundAlternatives.sort(Comparator.comparingInt(AlternativeRouteDTO::totalEstimatedFlightTime));

        log.info("Search finished. Returned {} options sorted by speed", foundAlternatives.size());

        return foundAlternatives;
    }

    // --- Métodos Auxiliares ---

    private List<RouteViewDTO> getOutgoingRoutes(String iataCode) {
        List<RouteViewDTO> routes = routeRepository
                .findByOrigin_IataCode_Code(iataCode.toUpperCase(), org.springframework.data.domain.Pageable.unpaged())
                .filter(r -> r.getStatus() == RouteStatus.ACTIVE)
                .map(this::convertToDTO)
                .toList();

        for (String peerUrl : peerUrls) {
            try {
                String url = UriComponentsBuilder.fromUriString(peerUrl)
                        .pathSegment("api", "routes", "airport", iataCode.toUpperCase())
                        .queryParam("size", 2000)
                        .toUriString();
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-P2P-Hops", "0");
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode embedded = HalResponseUtils.firstEmbeddedArray(
                        objectMapper.readTree(response.getBody()));
                if (embedded != null) {
                    for (JsonNode node : embedded) {
                        RouteViewDTO route = objectMapper.treeToValue(node, RouteViewDTO.class);
                        if ("ACTIVE".equalsIgnoreCase(route.status())) {
                            routes.add(route);
                        }
                    }
                }
            } catch (Exception exception) {
                log.warn("Peer route search failed for {}", peerUrl, exception);
            }
        }

        Map<String, RouteViewDTO> unique = new LinkedHashMap<>();
        routes.forEach(route -> unique.merge(route.routeId(), route, this::newerRouteVersion));
        return new ArrayList<>(unique.values());
    }

    private boolean isAirportInPath(List<RouteViewDTO> path, String destinationIata) {
        for (RouteViewDTO route : path) {
            if (route.originIata().equalsIgnoreCase(destinationIata)) return true;
        }
        return false;
    }

    private AlternativeRouteDTO convertToAlternativeDTO(List<RouteViewDTO> path) {
        List<RouteViewDTO> legDTOs = new ArrayList<>();
        int totalTime = 0;
        double totalDistance = 0.0;

        for (int i = 0; i < path.size(); i++) {
            RouteViewDTO route = path.get(i);
            legDTOs.add(new RouteViewDTO(
                    route.routeId(), route.originIata(), route.destinationIata(),
                    route.status(), route.minimumCapacity()
            ));

            totalTime += route.estimatedFlightTime() != null ? route.estimatedFlightTime() : 0;
            totalDistance += route.minimumRange() != null ? route.minimumRange() : 0.0;

            // Se for escala, soma os 60 minutos do Minimum Connection Time
            if (i < path.size() - 1) {
                totalTime += MINIMUM_CONNECTING_TIME_MINUTES;
            }
        }

        return new AlternativeRouteDTO(legDTOs, totalTime, totalDistance);
    }

    private RouteViewDTO convertToDTO(Route route) {
        return new RouteViewDTO(route.getRouteId().id(),
                route.getOrigin().getIataCode().code(),
                route.getDestination().getIataCode().code(),
                route.getStatus().name(), route.getMinimumCapacity(),
                route.getLastModified(), route.getOriginNodeId(),
                route.getMinimumRange(), route.getEstimatedFlightTime());
    }

    private RouteViewDTO newerRouteVersion(RouteViewDTO current, RouteViewDTO candidate) {
        if (current.lastModified() == null) return candidate;
        if (candidate.lastModified() == null) return current;
        int comparison = candidate.lastModified().compareTo(current.lastModified());
        if (comparison != 0) return comparison > 0 ? candidate : current;
        String currentNode = current.originNodeId() == null ? "" : current.originNodeId();
        String candidateNode = candidate.originNodeId() == null ? "" : candidate.originNodeId();
        return candidateNode.compareTo(currentNode) > 0 ? candidate : current;
    }
}