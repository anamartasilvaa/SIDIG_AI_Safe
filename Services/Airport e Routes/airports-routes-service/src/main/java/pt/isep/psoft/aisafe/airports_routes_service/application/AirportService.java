package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Value;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.*;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.NotFoundException;
import pt.isep.psoft.aisafe.airports_routes_service.domain.*;
import pt.isep.psoft.aisafe.airports_routes_service.readmodel.AirportProjector;
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
                if (receivedAt != null && isNewer(receivedAt, receivedNode, current.getLastModified(), current.getOriginNodeId())) {
                    current.applyReplicationData(dto.name(), dto.city(), dto.country(), dto.timezone(),
                            AirportType.valueOf(dto.type().toUpperCase()), new Coordinates(dto.latitude(), dto.longitude()),
                            current.getStatus(), null, null, dto.imageUrl(), null, null);
                    current.applyReplicationMetadata(receivedAt, receivedNode);
                    airportRepository.save(current);
                    return project(current);
                }
                auditService.record("CREATE", dto.iataCode(), "SUCCESS", true);
                return project(current);
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
        return project(saved);
    }



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
            if (isReplication && receivedAt != null && !isNewer(receivedAt, receivedNode, airport.getLastModified(), airport.getOriginNodeId())) {
                return project(airport);
            }
            airport.updateStatus(newStatus);
            airport.applyReplicationMetadata(isReplication ? receivedAt : Instant.now(), isReplication ? receivedNode : nodeId);
            Airport saved = airportRepository.save(airport);
            if (!isReplication) {
                replicateStatus(iataCode, status, saved.getLastModified(), saved.getOriginNodeId());
            }
            auditService.record("UPDATE", iataCode, "SUCCESS", isReplication);
            return project(saved);
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
            String contentType = file.getContentType() == null
                    ? "application/octet-stream" : file.getContentType();
            String fileUrl = "data:" + contentType + ";base64,"
                    + Base64.getEncoder().encodeToString(file.getBytes());
            airport.updateImage(fileUrl);
            airport.applyReplicationMetadata(isReplication ? null : Instant.now(), isReplication ? null : nodeId);

            airport = airportRepository.save(airport);
            // A imagem so e replicada por AMQP (por HTTP o endpoint de destino e multipart).
            if (!isReplication && replicationOutbox.isAmqp()) {
                replicationOutbox.enqueue(peerUrls, "PATCH", "/api/airports/" + iataCode.toUpperCase() + "/image",
                        fileUrl, airport.getLastModified(), airport.getOriginNodeId());
            }
            auditService.record("UPDATE", iataCode, "SUCCESS", isReplication);
            return project(airport);

        } catch (java.io.IOException e) {
            auditService.record("UPDATE", iataCode, "ERROR", isReplication);
            throw new RuntimeException("Failed to save airport image: " + e.getMessage());
        }
    }

    /** Aplica uma imagem recebida por evento (last-writer-wins; eventos mais antigos sao ignorados). */
    @Transactional
    public AirportViewDTO applyReplicatedImage(String iataCode, String imageDataUrl,
                                               Instant receivedAt, String receivedNode) {
        Airport airport = airportRepository.findByIataCodeString(iataCode.toUpperCase())
                .orElseThrow(() -> {
                    auditService.record("UPDATE", iataCode, "ERROR", true);
                    return new NotFoundException("Airport not found: " + iataCode);
                });
        if (receivedAt != null && !isNewer(receivedAt, receivedNode, airport.getLastModified(), airport.getOriginNodeId())) {
            return project(airport);
        }
        airport.updateImage(imageDataUrl);
        airport.applyReplicationMetadata(receivedAt, receivedNode);
        airport = airportRepository.save(airport);
        auditService.record("UPDATE", iataCode, "SUCCESS", true);
        return project(airport);
    }

    // --- Snapshot (instancia que arranca com a base de dados vazia) ---

    /** Todos os aeroportos do modelo de escrita, com lastModified e originNodeId (GET /internal/snapshot). */
    @Transactional(readOnly = true)
    public List<AirportSnapshotDTO> exportSnapshot() {
        return airportRepository.findAll().stream()
                .map(a -> new AirportSnapshotDTO(
                        a.getIataCode().code(), a.getName(), a.getCity(), a.getCountry(), a.getTimezone(),
                        a.getType().name(), a.getStatus().name(),
                        a.getCoordinates().latitude(), a.getCoordinates().longitude(),
                        a.getRunways().stream()
                                .map(r -> new RunwayDTO(r.getName(), r.getLength(), r.getOrientation())).toList(),
                        a.getFacilities().stream()
                                .map(f -> new FacilityDTO(f.getType(), f.getDescription())).toList(),
                        a.getImageUrl(), a.getOperationalHours(), a.getContactInformation(),
                        a.getLastModified(), a.getOriginNodeId()))
                .toList();
    }

    /**
     * Aplica um aeroporto recebido no snapshot de outra instancia. Nao gera eventos (a outra instancia ja os tem).
     * Usa a mesma regra dos eventos (last-writer-wins), por isso um evento mais recente ja recebido pela fila
     * nunca e substituido por um snapshot mais antigo.
     * @return true se a copia local foi criada ou atualizada.
     */
    @Transactional
    public boolean applySnapshot(AirportSnapshotDTO s) {
        Optional<Airport> existing = airportRepository.findByIataCodeString(s.iataCode().toUpperCase());
        if (existing.isPresent() && !snapshotWins(s.lastModified(), s.originNodeId(),
                existing.get().getLastModified(), existing.get().getOriginNodeId())) {
            return false;
        }
        AirportType type = AirportType.valueOf(s.type().toUpperCase());
        Coordinates coordinates = new Coordinates(s.latitude(), s.longitude());
        Airport airport = existing.orElseGet(() -> new Airport(new IATACode(s.iataCode()), s.name(), s.city(),
                s.country(), s.timezone(), type, coordinates));
        List<Runway> runways = s.runways() == null ? List.of() : s.runways().stream()
                .map(r -> new Runway(r.name(), r.length(), r.orientation())).toList();
        List<Facility> facilities = s.facilities() == null ? List.of() : s.facilities().stream()
                .map(f -> new Facility(f.type(), f.description())).toList();
        airport.applyReplicationData(s.name(), s.city(), s.country(), s.timezone(), type, coordinates,
                AirportStatus.valueOf(s.status().toUpperCase()), runways, facilities, s.imageUrl(),
                s.operationalHours(), s.contactInformation());
        airport.applyReplicationMetadata(s.lastModified(), s.originNodeId());
        project(airportRepository.save(airport));
        return true;
    }

    /**
     * Regra do snapshot: last-writer-wins, exceto quando a copia local veio dos dados iniciais
     * (originNodeId "local", criada pelo AirportBootstrapper no arranque): essa e sempre substituida
     * pelos dados reais da outra instancia.
     */
    static boolean snapshotWins(Instant incoming, String incomingNode, Instant current, String currentNode) {
        if (incoming == null) return false;
        if ("local".equals(currentNode) && !"local".equals(incomingNode)) return true;
        if (current == null) return true;
        int c = incoming.compareTo(current);
        return c > 0 || (c == 0 && String.valueOf(incomingNode).compareTo(String.valueOf(currentNode)) > 0);
    }

    /** CQRS: modelo de leitura atualizado pelo projetor (opcional para permitir testes unitarios). */
    @Autowired(required = false)
    private AirportProjector airportProjector;

    /** Mapeia o agregado e atualiza o modelo de leitura na mesma transacao do comando. */
    private AirportViewDTO project(Airport airport) {
        AirportViewDTO dto = mapToDTO(airport);
        if (airportProjector != null) {
            airportProjector.project(dto);
        }
        return dto;
    }

    /** Reconstroi todo o modelo de leitura a partir do modelo de escrita. */
    @Transactional
    public int rebuildReadModel() {
        if (airportProjector == null) return 0;
        List<Airport> all = airportRepository.findAll();
        all.forEach(airport -> airportProjector.project(mapToDTO(airport)));
        return all.size();
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

        if (isReplication && receivedAt != null && !isNewer(receivedAt, receivedNode, airport.getLastModified(), airport.getOriginNodeId())) {
            return project(airport);
        }
        airport.updateDetails(dto.operationalHours(), dto.contactInformation());
        airport.applyReplicationMetadata(isReplication ? receivedAt : Instant.now(), isReplication ? receivedNode : nodeId);
        airport = airportRepository.save(airport);
        if (!isReplication) {
            replicateUpdateDetails(iataCode, dto, airport.getLastModified(), airport.getOriginNodeId());
        }
        auditService.record("UPDATE", iataCode, "SUCCESS", isReplication);
        return project(airport);
    }

    private void replicateCreate(RegisterAirportDTO dto, Airport airport) {
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