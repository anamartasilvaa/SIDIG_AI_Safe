package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.*;
import pt.isep.psoft.aisafe.airports_routes_service.domain.*;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;
import java.util.List;
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
    private final String peerUrl;

    public AirportService(AirportRepository airportRepository,
                          RestTemplate restTemplate,
                          ObjectMapper objectMapper,
                          @Value("${peer.url:http://localhost:8092}") String peerUrl) {
        this.airportRepository = airportRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.peerUrl = peerUrl;
    }

    @Transactional
    public AirportViewDTO registerAirport(RegisterAirportDTO dto, boolean isReplication) {
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

        Airport saved = airportRepository.save(airport);
        if (!isReplication) {
            replicateCreate(dto);
        }
        return mapToDTO(saved);
    }

    @Transactional(readOnly = true)
    public AirportViewDTO getAirportByIataCode(String iataCode) {
        String cleanCode = iataCode.trim().toUpperCase();
        Optional<Airport> localAirport = airportRepository.findByIataCodeString(cleanCode);
        if (localAirport.isPresent()) {
            return mapToDTO(localAirport.get());
        }

        String url = UriComponentsBuilder.fromUriString(peerUrl)
                .pathSegment("api", "airports", cleanCode)
                .toUriString();
        try {
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null || body.isNull()) {
                throw airportNotFound(cleanCode);
            }

            // The peer endpoint returns an EntityModel, whose DTO is under "content".
            JsonNode dtoNode = body.has("content") ? body.get("content") : body;
            return objectMapper.treeToValue(dtoNode, AirportViewDTO.class);
        } catch (HttpClientErrorException.NotFound exception) {
            throw airportNotFound(cleanCode);
        } catch (HttpClientErrorException exception) {
            throw new IllegalStateException("Peer airport lookup failed with status "
                    + exception.getStatusCode(), exception);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid airport response from peer", exception);
        }
    }

    private IllegalArgumentException airportNotFound(String iataCode) {
        return new IllegalArgumentException("Airport not found with IATA Code: " + iataCode);
    }

    @Transactional(readOnly = true)
    public List<AirportViewDTO> searchAirports(String name, String city, String country) {
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

        return airports.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    // US109 - Update Status
    @Transactional
    public AirportViewDTO updateAirportStatus(String iataCode, String status, boolean isReplication) {
        Airport airport = airportRepository.findByIataCodeString(iataCode.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Airport not found"));

        try {
            AirportStatus newStatus = AirportStatus.valueOf(status.trim().toUpperCase());
            airport.updateStatus(newStatus);
            Airport saved = airportRepository.save(airport);
            if (!isReplication) {
                replicateStatus(iataCode, status);
            }
            return mapToDTO(saved);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status. Use: OPERATIONAL, CLOSED, or UNDER_MAINTENANCE");
        }
    }

    @Transactional
    public AirportViewDTO updateAirportImage(String iataCode, org.springframework.web.multipart.MultipartFile file,
                                             boolean isReplication) {

        Airport airport = airportRepository.findByIataCodeString(iataCode.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Airport not found: " + iataCode));

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

            airport = airportRepository.save(airport);

            return mapToDTO(airport);

        } catch (java.io.IOException e) {
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
                airport.getContactInformation()
        );
    }

    @Transactional
    public AirportViewDTO updateAirportDetails(String iataCode, UpdateAirportDetailsDTO dto, boolean isReplication) {
        Airport airport = airportRepository.findByIataCodeString(iataCode.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Airport not found: " + iataCode));

        airport.updateDetails(dto.operationalHours(), dto.contactInformation());
        airport = airportRepository.save(airport);
        if (!isReplication) {
            replicateUpdateDetails(iataCode, dto);
        }

        return mapToDTO(airport);
    }

    private void replicateCreate(RegisterAirportDTO dto) {
        String url = UriComponentsBuilder.fromUriString(peerUrl)
                .pathSegment("api", "airports")
                .toUriString();
        try {
            restTemplate.postForEntity(url, replicationEntity(dto), Void.class);
        } catch (ResourceAccessException exception) {
            log.warn("Falha ao replicar para o peer", exception);
        }
    }

    private void replicateUpdateDetails(String iataCode, UpdateAirportDetailsDTO dto) {
        String url = UriComponentsBuilder.fromUriString(peerUrl)
                .pathSegment("api", "airports", iataCode.toUpperCase(), "details")
                .toUriString();
        try {
            restTemplate.patchForObject(url, replicationEntity(dto), Void.class);
        } catch (ResourceAccessException exception) {
            log.warn("Falha ao replicar para o peer", exception);
        }
    }

    private void replicateStatus(String iataCode, String status) {
        String url = UriComponentsBuilder.fromUriString(peerUrl)
                .pathSegment("api", "airports", iataCode.toUpperCase(), "status")
                .toUriString();
        try {
            restTemplate.patchForObject(url, replicationEntity(status), Void.class);
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

    //  US211: View airports grouped by region or country
    @Transactional(readOnly = true)
    public Object getAirportsGrouped(String groupBy) {
        List<Airport> airports = airportRepository.findAll();

        if ("both".equalsIgnoreCase(groupBy)) {
            return airports.stream()
                    .map(this::mapToDTO)
                    .collect(Collectors.groupingBy(
                            this::extractRegion,
                            Collectors.groupingBy(dto -> dto.country() != null ? dto.country() : "Unknown Country")
                    ));
        }

        if ("region".equalsIgnoreCase(groupBy)) {
            return airports.stream()
                    .map(this::mapToDTO)
                    .collect(Collectors.groupingBy(this::extractRegion));
        }

        return airports.stream()
                .map(this::mapToDTO)
                .collect(Collectors.groupingBy(dto -> dto.country() != null ? dto.country() : "Unknown Country"));
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

            return importedAirports;

        } catch (java.io.IOException e) {
            throw new RuntimeException("Error parsing the CSV file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Error in CSV formatting/data: " + e.getMessage());
        }
    }
    }
