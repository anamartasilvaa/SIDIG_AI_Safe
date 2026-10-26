package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.*;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.NotFoundException;
import pt.isep.psoft.aisafe.airports_routes_service.domain.*;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;
import pt.isep.psoft.aisafe.airports_routes_service.util.HalResponseUtils;
import pt.isep.psoft.aisafe.airports_routes_service.util.PartialResultContext;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AirportService {
    private static final Logger log = LoggerFactory.getLogger(AirportService.class);

    private final AirportRepository airportRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ReplicationOutbox replicationOutbox;
    private final AuditService auditService;
    private final List<String> peerUrls;
    private static final String HOPS_HEADER = "X-P2P-Hops";
    private final int maxForwardingHops;
    @Value("${peer.node-id:${server.port:local}}")
    private String nodeId = "local";

    public AirportService(AirportRepository airportRepository,
                          RestTemplate restTemplate,
                          ObjectMapper objectMapper, ReplicationOutbox replicationOutbox, AuditService auditService,
                          @Value("${peer.urls:}") List<String> peerUrls,
                          @Value("${peer.forwarding.max-hops:1}") int maxForwardingHops) {
        this.airportRepository = airportRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.replicationOutbox = replicationOutbox;
        this.auditService = auditService;
        this.peerUrls = peerUrls.stream().map(String::trim).filter(url -> !url.isBlank()).toList();
        this.maxForwardingHops = maxForwardingHops;
    }

    @Transactional
    public AirportViewDTO registerAirport(RegisterAirportDTO dto, boolean isReplication) {
        return registerAirport(dto, isReplication, dto.lastModified(), dto.originNodeId());
    }
    @Transactional
    public AirportViewDTO registerAirport(RegisterAirportDTO dto, boolean isReplication, Instant receivedAt, String receivedNode) {
        if (!isReplication && airportRepository.findByIataCodeString(dto.iataCode().toUpperCase()).isPresent()) {
            throw new IllegalStateException("Airport already exists with IATA Code: " + dto.iataCode());
        }
        if (isReplication) {
            Optional<Airport> existing = airportRepository.findByIataCodeString(dto.iataCode().toUpperCase());
            if (existing.isPresent()) {
                Airport current = existing.get();
                if (isNewer(receivedAt, receivedNode, current.getLastModified(), current.getOriginNodeId())) {
                    current.applyReplicationData(dto.name(), dto.city(), dto.country(), dto.timezone(),
                            AirportType.valueOf(dto.type().toUpperCase()), new Coordinates(dto.latitude(), dto.longitude()),
                            current.getStatus(), null, null, dto.imageUrl(), null, null);
                    current.applyReplicationMetadata(receivedAt, receivedNode);
                    airportRepository.save(current);
                    return mapToDTO(current);
                }
                auditService.record("CREATE", dto.iataCode(), "SUCCESS", true);
                return mapToDTO(current);
            }
        }
        Airport airport = new Airport(
                new IATACode(dto.iataCode()),
                dto.name(),
                dto.city(),
                dto.country(),
                dto.timezone(),
                AirportType.valueOf(dto.type().toUpperCase()),
                new Coordinates(dto.latitude(), dto.longitude())
        );

        if (dto.runways() != null) {
            dto.runways().forEach(r ->
                    airport.addRunway(new Runway(r.name(), r.length(), r.orientation()))
            );
        }


        if (dto.imageUrl() != null && !dto.imageUrl().isBlank()) {
            airport.updateImage(dto.imageUrl());
        }

        if (dto.facilities() != null) {
            dto.facilities().forEach(f ->
                    airport.addFacility(new Facility(f.type(), f.description()))
            );
        }

        airport.applyReplicationMetadata(isReplication ? receivedAt : Instant.now(),
                isReplication ? receivedNode : nodeId);
        Airport saved = airportRepository.save(airport);
        if (!isReplication) {
            replicateCreate(dto, saved);
        }
        auditService.record("CREATE", dto.iataCode(), "SUCCESS", isReplication);
        return mapToDTO(saved);
    }

    @Transactional(readOnly = true)
    public AirportViewDTO getAirportByIataCode(String iataCode) {
        return getAirportByIataCode(iataCode, 0);
    }

    public AirportViewDTO getAirportByIataCode(String iataCode, int hops) {
        hops = Math.max(0, hops);
        String cleanCode = iataCode.trim().toUpperCase();
        Optional<Airport> localAirport = airportRepository.findByIataCodeString(cleanCode);
        if (localAirport.isPresent()) {
            return mapToDTO(localAirport.get());
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
                ResponseEntity<JsonNode> response = restTemplate.exchange(
                        url, HttpMethod.GET, p2pEntity(hops + 1), JsonNode.class);
                JsonNode body = response.getBody();
                if (body == null || body.isNull()) {
                    continue;
                }
                JsonNode dtoNode = body.has("content") ? body.get("content") : body;
                return objectMapper.treeToValue(dtoNode, AirportViewDTO.class);
            } catch (HttpClientErrorException.NotFound exception) {
                log.debug("Peer airport lookup returned 404 for {}", peerUrl);
            } catch (RestClientException exception) {
                peerFailure = true;
                log.warn("Peer airport lookup failed with status {} for {}",
                        exception instanceof HttpClientErrorException httpException
                                ? httpException.getStatusCode() : "peer error", peerUrl);
            } catch (JsonProcessingException exception) {
                peerFailure = true;
                log.warn("Invalid airport response from peer {}", peerUrl, exception);
            }
        }
        if (peerFailure) {
            throw new ResourceAccessException("One or more peers were unavailable");
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
        List<Airport> airports;

        if (name != null && !name.isBlank()) {
            airports = airportRepository.findByNameContainingIgnoreCase(name);
        } else if (city != null && !city.isBlank()) {
            airports = airportRepository.findByCityIgnoreCase(city);
        } else if (country != null && !country.isBlank()) {
            airports = airportRepository.findByCountryIgnoreCase(country);
        } else {
            airports = airportRepository.findAll();
        }

        List<AirportViewDTO> results = airports.stream()
                .map(this::mapToDTO)
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

    // AI-assisted (GitHub Copilot): keeps the newest representation during P2P aggregation.
    private AirportViewDTO newerAirportVersion(AirportViewDTO current, AirportViewDTO candidate) {
        if (current.lastModified() == null) return candidate;
        if (candidate.lastModified() == null) return current;
        int timestampComparison = candidate.lastModified().compareTo(current.lastModified());
        if (timestampComparison != 0) return timestampComparison > 0 ? candidate : current;
        String currentNode = current.originNodeId() == null ? "" : current.originNodeId();
        String candidateNode = candidate.originNodeId() == null ? "" : candidate.originNodeId();
        return candidateNode.compareTo(currentNode) > 0 ? candidate : current;
    }

    // US109 - Update Status
    @Transactional
    public AirportViewDTO updateAirportStatus(String iataCode, String status, boolean isReplication) {
        return updateAirportStatus(iataCode, status, isReplication, null, null);
    }
    @Transactional
    public AirportViewDTO updateAirportStatus(String iataCode, String status, boolean isReplication,
                                              Instant receivedAt, String receivedNode) {
        Airport airport = airportRepository.findByIataCodeString(iataCode.toUpperCase())
                .orElseThrow(() -> {
                    auditService.record("UPDATE", iataCode, "ERROR", isReplication);
                    return new NotFoundException("Airport not found");
                });

        try {
            AirportStatus newStatus = AirportStatus.valueOf(status.trim().toUpperCase());
            if (isReplication && !isNewer(receivedAt, receivedNode, airport.getLastModified(), airport.getOriginNodeId()))
                return mapToDTO(airport);
            airport.updateStatus(newStatus);
            airport.applyReplicationMetadata(isReplication ? receivedAt : Instant.now(), isReplication ? receivedNode : nodeId);
            Airport saved = airportRepository.save(airport);
            if (!isReplication) {
                replicateStatus(iataCode, status, saved.getLastModified(), saved.getOriginNodeId());
            }
            auditService.record("UPDATE", iataCode, "SUCCESS", isReplication);
            return mapToDTO(saved);
        } catch (IllegalArgumentException e) {
            auditService.record("UPDATE", iataCode, "ERROR", isReplication);
            throw new IllegalArgumentException("Invalid status. Use: OPERATIONAL, CLOSED, or UNDER_MAINTENANCE");
        }
    }

    @Transactional
    public AirportViewDTO updateAirportImage(String iataCode, org.springframework.web.multipart.MultipartFile file,
                                             boolean isReplication) {
        Airport airport = airportRepository.findByIataCodeString(iataCode.toUpperCase())
                .orElseThrow(() -> {
                    auditService.record("UPDATE", iataCode, "ERROR", isReplication);
                    return new NotFoundException("Airport not found: " + iataCode);
                });

        try {
            String projectDirectory = System.getProperty("user.dir");
            java.nio.file.Path uploadPath = java.nio.file.Paths.get(projectDirectory, "uploads");

            if (!java.nio.file.Files.exists(uploadPath)) {
                java.nio.file.Files.createDirectories(uploadPath);
            }

            String fileName = iataCode.toUpperCase() + "_" + file.getOriginalFilename();
            java.nio.file.Path filePath = uploadPath.resolve(fileName);

            java.nio.file.Files.copy(file.getInputStream(), filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            String fileUrl = "/uploads/" + fileName;
            airport.updateImage(fileUrl);
            airport.applyReplicationMetadata(isReplication ? null : Instant.now(), isReplication ? null : nodeId);

            airport = airportRepository.save(airport);
            auditService.record("UPDATE", iataCode, "SUCCESS", isReplication);
            return mapToDTO(airport);

        } catch (java.io.IOException e) {
            auditService.record("UPDATE", iataCode, "ERROR", isReplication);
            throw new RuntimeException("Failed to save airport image: " + e.getMessage());
        }
    }

    private AirportViewDTO mapToDTO(Airport airport) {
        return new AirportViewDTO(
                airport.getIataCode().code(),
                airport.getName(),
                airport.getCity(),
                airport.getCountry(),
                airport.getTimezone(),
                airport.getType().toString(),
                airport.getStatus().toString(),
                airport.getRunways().stream()
                        .map(r -> new RunwayDTO(r.getName(), r.getLength(), r.getOrientation()))
                        .collect(Collectors.toList()),
                airport.getFacilities().stream()
                        .map(f -> new FacilityDTO(f.getType(), f.getDescription()))
                        .collect(Collectors.toList()),
                airport.getImageUrl(),
                airport.getOperationalHours(),
                airport.getContactInformation(), airport.getLastModified(), airport.getOriginNodeId()
        );
    }

    @Transactional
    public AirportViewDTO updateAirportDetails(String iataCode, UpdateAirportDetailsDTO dto, boolean isReplication) {
        return updateAirportDetails(iataCode, dto, isReplication, null, null);
    }
    @Transactional
    public AirportViewDTO updateAirportDetails(String iataCode, UpdateAirportDetailsDTO dto, boolean isReplication,
                                                Instant receivedAt, String receivedNode) {
        Airport airport = airportRepository.findByIataCodeString(iataCode.toUpperCase())
                .orElseThrow(() -> {
                    auditService.record("UPDATE", iataCode, "ERROR", isReplication);
                    return new NotFoundException("Airport not found: " + iataCode);
                });

        if (isReplication && !isNewer(receivedAt, receivedNode, airport.getLastModified(), airport.getOriginNodeId()))
            return mapToDTO(airport);
        airport.updateDetails(dto.operationalHours(), dto.contactInformation());
        airport.applyReplicationMetadata(isReplication ? receivedAt : Instant.now(), isReplication ? receivedNode : nodeId);
        airport = airportRepository.save(airport);
        if (!isReplication) {
            replicateUpdateDetails(iataCode, dto, airport.getLastModified(), airport.getOriginNodeId());
        }
        auditService.record("UPDATE", iataCode, "SUCCESS", isReplication);
        return mapToDTO(airport);
    }

    private void replicateCreate(RegisterAirportDTO dto, Airport airport) {
        // AI-assisted (GitHub Copilot): propagate metadata when Spring has injected the configured node id.
        RegisterAirportDTO payload = "local".equals(nodeId) ? dto : new RegisterAirportDTO(dto.iataCode(), dto.name(), dto.city(), dto.country(),
                dto.timezone(), dto.type(), dto.latitude(), dto.longitude(), dto.runways(), dto.facilities(),
                dto.imageUrl(), airport.getLastModified(), airport.getOriginNodeId());
        replicationOutbox.enqueue(peerUrls, "POST", "/api/airports", payload);
    }

    private void replicateUpdateDetails(String iataCode, UpdateAirportDetailsDTO dto, Instant lastModified, String originNodeId) {
        replicationOutbox.enqueue(peerUrls, "PATCH", "/api/airports/"+iataCode.toUpperCase()+"/details", dto, lastModified, originNodeId);
    }

    private void replicateStatus(String iataCode, String status, Instant lastModified, String originNodeId) {
        replicationOutbox.enqueue(peerUrls, "PATCH", "/api/airports/"+iataCode.toUpperCase()+"/status", status, lastModified, originNodeId);
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

    private <T> HttpEntity<T> replicationEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-P2P-Sync", "true");
        return new HttpEntity<>(body, headers);
    }

    //  US211: View airports grouped by region or country
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
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    builder.toUriString(), HttpMethod.GET, p2pEntity(hops + 1), JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null || body.isNull()) return List.of();
            JsonNode airportsNode = HalResponseUtils.firstEmbeddedArray(body);
            List<AirportViewDTO> airports = new ArrayList<>();
            if (airportsNode != null) {
                for (JsonNode node : airportsNode) {
                    airports.add(objectMapper.treeToValue(node, AirportViewDTO.class));
                }
            }
            return airports;
        } catch (HttpClientErrorException.NotFound exception) {
            return List.of();
        } catch (HttpClientErrorException | ResourceAccessException exception) {
            PartialResultContext.markPartial();
            log.warn("Peer airport search failed for {}", builder, exception);
            return List.of();
        } catch (JsonProcessingException | RestClientException exception) {
            PartialResultContext.markPartial();
            log.warn("Invalid or unavailable airport response from peer {}", builder, exception);
            return List.of();
        }
    }

    private String extractRegion(AirportViewDTO dto) {
        if (dto.timezone() != null && dto.timezone().contains("/")) {
            return dto.timezone().split("/")[0];
        }
        return "Unknown Region";
    }

    // US225
    @Transactional
    public List<AirportViewDTO> importAirportsFromCsv(org.springframework.web.multipart.MultipartFile file) {
        if (file.isEmpty()) {
            auditService.record("CREATE", "CSV", "ERROR");
            throw new IllegalArgumentException("The uploaded CSV file is empty.");
        }

        List<AirportViewDTO> importedAirports = new java.util.ArrayList<>();

        try (java.io.BufferedReader fileReader = new java.io.BufferedReader(new java.io.InputStreamReader(file.getInputStream(), "UTF-8"));
             org.apache.commons.csv.CSVParser csvParser = new org.apache.commons.csv.CSVParser(fileReader,
                     org.apache.commons.csv.CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim())) {

            for (org.apache.commons.csv.CSVRecord csvRecord : csvParser) {
                RegisterAirportDTO dto = new RegisterAirportDTO(
                        csvRecord.get("iataCode"),
                        csvRecord.get("name"),
                        csvRecord.get("city"),
                        csvRecord.get("country"),
                        csvRecord.get("timezone"),
                        csvRecord.get("type"),
                        Double.parseDouble(csvRecord.get("latitude")),
                        Double.parseDouble(csvRecord.get("longitude")),
                        List.of(), // runways
                        List.of(), // facilities
                        null       // imageUrl
                );

                AirportViewDTO savedAirport = registerAirport(dto, false);
                importedAirports.add(savedAirport);
            }

            auditService.record("CREATE", "CSV", "SUCCESS");
            return importedAirports;

        } catch (java.io.IOException e) {
            auditService.record("CREATE", "CSV", "ERROR");
            throw new RuntimeException("Error parsing the CSV file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            auditService.record("CREATE", "CSV", "ERROR");
            throw new IllegalArgumentException("Error in CSV formatting/data: " + e.getMessage());
        }
    }
}
