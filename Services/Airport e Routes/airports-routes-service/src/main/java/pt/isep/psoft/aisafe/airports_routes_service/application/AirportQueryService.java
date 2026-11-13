package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.NotFoundException;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportViewDTO;
import pt.isep.psoft.aisafe.airports_routes_service.readmodel.AirportReadModel;
import pt.isep.psoft.aisafe.airports_routes_service.readmodel.AirportReadRepository;
import pt.isep.psoft.aisafe.airports_routes_service.util.HalResponseUtils;
import pt.isep.psoft.aisafe.airports_routes_service.util.PartialResultContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CQRS, lado das QUERIES (leitura) dos aeroportos.
 *
 * Le exclusivamente do modelo de leitura (tabela airport_read_model), que e mantido pelo
 * AirportProjector a partir dos comandos e dos eventos recebidos do broker. Nao toca no
 * modelo de escrita (AirportRepository / entidade Airport).
 * Mantem o encaminhamento P2P das leituras (X-P2P-Hops) da 1.a parte.
 */
@Service
public class AirportQueryService {
    private static final Logger log = LoggerFactory.getLogger(AirportQueryService.class);
    private static final String HOPS_HEADER = "X-P2P-Hops";

    private final AirportReadRepository readRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final List<String> peerUrls;
    private final int maxForwardingHops;

    public AirportQueryService(AirportReadRepository readRepository,
                               RestTemplate restTemplate,
                               ObjectMapper objectMapper,
                               @Value("${peer.urls:}") List<String> peerUrls,
                               @Value("${peer.forwarding.max-hops:1}") int maxForwardingHops) {
        this.readRepository = readRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.peerUrls = peerUrls.stream().map(String::trim).filter(url -> !url.isBlank()).toList();
        this.maxForwardingHops = maxForwardingHops;
    }

    private AirportViewDTO toView(AirportReadModel model) {
        try {
            return objectMapper.readValue(model.getViewJson(), AirportViewDTO.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Modelo de leitura invalido para " + model.getIataCode(), exception);
        }
    }

    @Transactional(readOnly = true)
    public AirportViewDTO getAirportByIataCode(String iataCode) {
        return getAirportByIataCode(iataCode, 0);
    }

    public AirportViewDTO getAirportByIataCode(String iataCode, int hops) {
        hops = Math.max(0, hops);
        String cleanCode = iataCode.trim().toUpperCase();
        Optional<AirportReadModel> localAirport = readRepository.findById(cleanCode);
        if (localAirport.isPresent()) {
            return toView(localAirport.get());
        }

        if (hops >= maxForwardingHops) {
            throw airportNotFound(cleanCode);
        }

        boolean peerFailure = false;
        for (String peerUrl : peerUrls) {
            String url = UriComponentsBuilder.fromUriString(peerUrl)
                    .pathSegment("api", "airports", cleanCode)
                    .toUriString();
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, p2pEntity(hops + 1), String.class);
                String responseBody = response.getBody();
                if (responseBody == null || responseBody.isBlank()) {
                    continue;
                }
                JsonNode body = objectMapper.readTree(responseBody);
                if (body == null || body.isNull()) {
                    continue;
                }
                JsonNode dtoNode = body.has("content") ? body.get("content") : body;
                return objectMapper.treeToValue(dtoNode, AirportViewDTO.class);
            } catch (HttpClientErrorException.NotFound exception) {
                log.debug("Peer airport lookup returned 404 for {}", peerUrl);
            } catch (Exception exception) {
                peerFailure = true;
                log.warn("Peer airport lookup failed for {}", peerUrl, exception);
            }
        }
        if (peerFailure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "One or more peers were unavailable");
        }
        throw airportNotFound(cleanCode);
    }

    private NotFoundException airportNotFound(String iataCode) {
        return new NotFoundException("Airport not found with IATA Code: " + iataCode);
    }

    @Transactional(readOnly = true)
    public List<AirportViewDTO> searchAirports(String name, String city, String country) {
        return searchAirports(name, city, country, 0);
    }

    public List<AirportViewDTO> searchAirports(String name, String city, String country, int hops) {
        hops = Math.max(0, hops);
        PartialResultContext.start();
        List<AirportReadModel> airports;

        if (name != null && !name.isBlank()) {
            airports = readRepository.findByNameContainingIgnoreCase(name);
        } else if (city != null && !city.isBlank()) {
            airports = readRepository.findByCityIgnoreCase(city);
        } else if (country != null && !country.isBlank()) {
            airports = readRepository.findByCountryIgnoreCase(country);
        } else {
            airports = readRepository.findAll();
        }

        List<AirportViewDTO> results = airports.stream()
                .map(this::toView)
                .collect(Collectors.toList());
        if (hops < maxForwardingHops) {
            for (String peerUrl : peerUrls) {
                UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(peerUrl)
                        .pathSegment("api", "airports", "search");
                if (name != null) builder.queryParam("name", name);
                if (city != null) builder.queryParam("city", city);
                if (country != null) builder.queryParam("country", country);
                results.addAll(fetchPeerAirports(builder, hops));
            }
        }
        Map<String, AirportViewDTO> unique = new LinkedHashMap<>();
        results.forEach(airport -> unique.merge(airport.iataCode(), airport,
                this::newerAirportVersion));
        return new ArrayList<>(unique.values());
    }

    private AirportViewDTO newerAirportVersion(AirportViewDTO current, AirportViewDTO candidate) {
        if (current.lastModified() == null) return candidate;
        if (candidate.lastModified() == null) return current;
        int timestampComparison = candidate.lastModified().compareTo(current.lastModified());
        if (timestampComparison != 0) return timestampComparison > 0 ? candidate : current;
        String currentNode = current.originNodeId() == null ? "" : current.originNodeId();
        String candidateNode = candidate.originNodeId() == null ? "" : candidate.originNodeId();
        return candidateNode.compareTo(currentNode) > 0 ? candidate : current;
    }

    private <T> HttpEntity<T> p2pEntity(int hops) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HOPS_HEADER, String.valueOf(hops));
        return new HttpEntity<>(headers);
    }

    @Transactional(readOnly = true)
    public Object getAirportsGrouped(String groupBy) {
        return getAirportsGrouped(groupBy, 0);
    }

    public Object getAirportsGrouped(String groupBy, int hops) {
        hops = Math.max(0, hops);
        PartialResultContext.start();
        List<AirportViewDTO> airports = searchAirports(null, null, null, hops);

        if ("both".equalsIgnoreCase(groupBy)) {
            return airports.stream()
                    .collect(Collectors.groupingBy(
                            this::extractRegion,
                            Collectors.groupingBy(dto -> dto.country() != null ? dto.country() : "Unknown Country")
                    ));
        }

        if ("region".equalsIgnoreCase(groupBy)) {
            return airports.stream().collect(Collectors.groupingBy(this::extractRegion));
        }

        return airports.stream().collect(Collectors.groupingBy(dto -> dto.country() != null ? dto.country() : "Unknown Country"));
    }

    private List<AirportViewDTO> fetchPeerAirports(UriComponentsBuilder builder, int hops) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    builder.toUriString(), HttpMethod.GET, p2pEntity(hops + 1), String.class);
            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isBlank()) return List.of();
            JsonNode body = objectMapper.readTree(responseBody);
            if (body == null || body.isNull()) return List.of();
            JsonNode airportsNode = HalResponseUtils.firstEmbeddedArray(body);
            List<AirportViewDTO> airports = new ArrayList<>();
            if (airportsNode != null) {
                for (JsonNode node : airportsNode) {
                    airports.add(objectMapper.treeToValue(node, AirportViewDTO.class));
                }
            }
            return airports;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
            // O peer respondeu corretamente que não encontrou resultados, NÃO assinalar falha parcial
            return List.of();
        } catch (Exception exception) {
            // Houve um erro de rede/timeout/Indisponível, assinalar resultado parcial
            PartialResultContext.markPartial();
            log.warn("Peer airport search failed or returned error for {}", builder, exception);
            return List.of();
        }
    }
    private String extractRegion(AirportViewDTO dto) {
        if (dto.timezone() != null && dto.timezone().contains("/")) {
            return dto.timezone().split("/")[0];
        }
        return "Unknown Region";
    }
}
