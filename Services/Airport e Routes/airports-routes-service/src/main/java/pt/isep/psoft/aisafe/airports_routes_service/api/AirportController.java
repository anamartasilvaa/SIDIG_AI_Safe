package pt.isep.psoft.aisafe.airports_routes_service.api;

import jakarta.validation.Valid;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.airports_routes_service.util.PartialResultContext;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.*;
import pt.isep.psoft.aisafe.airports_routes_service.security.PeerReplicationAuthorizer;

import java.util.List;
import java.util.stream.Collectors;
import java.time.Instant;

@RestController
@RequestMapping("/api/airports")
public class AirportController {

    private final AirportService service;
    private final PeerReplicationAuthorizer replicationAuthorizer;

    public AirportController(AirportService service, PeerReplicationAuthorizer replicationAuthorizer) {
        this.service = service;
        this.replicationAuthorizer = replicationAuthorizer;
    }

    @PostMapping
    public ResponseEntity<EntityModel<AirportViewDTO>> createAirport(@RequestBody RegisterAirportDTO dto,
                                                                     @RequestHeader(value = "X-P2P-Sync", defaultValue = "false") boolean isReplication,
                                                                     @RequestHeader(value = "X-Last-Modified", required = false) String lastModified,
                                                                     @RequestHeader(value = "X-Origin-Node", required = false) String originNode) {

        boolean authorized = replicationAuthorizer.isAuthorized(isReplication);
        boolean effectiveReplication = isReplication && authorized;

        AirportViewDTO created = service.registerAirport(dto, effectiveReplication,
                lastModified != null ? parseInstant(lastModified) : dto.lastModified(),
                originNode != null ? originNode : dto.originNodeId());

        EntityModel<AirportViewDTO> resource = EntityModel.of(created);
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(created.iataCode(), 0)).withSelfRel());

        return new ResponseEntity<>(resource, HttpStatus.CREATED);
    }

    @GetMapping("/{iataCode}")
    public ResponseEntity<EntityModel<AirportViewDTO>> getAirport(
            @PathVariable String iataCode,
            @RequestHeader(value = "X-P2P-Hops", defaultValue = "0") int hops) {
        AirportViewDTO airport = service.getAirportByIataCode(iataCode.toUpperCase(), Math.max(0, hops));

        EntityModel<AirportViewDTO> resource = EntityModel.of(airport);

        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(iataCode, 0)).withSelfRel());

        return ResponseEntity.ok(resource);
    }

    @GetMapping("/search")
    public ResponseEntity<CollectionModel<EntityModel<AirportViewDTO>>> searchAirports(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String country,
            @RequestHeader(value = "X-P2P-Hops", defaultValue = "0") int hops) {

        List<AirportViewDTO> airports = service.searchAirports(name, city, country, Math.max(0, hops));

        List<EntityModel<AirportViewDTO>> airportResources = airports.stream()
                .map(dto -> EntityModel.of(dto,
                        WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(dto.iataCode(), 0)).withSelfRel()))
                .collect(Collectors.toList());

        HttpHeaders headers = new HttpHeaders();
        if (PartialResultContext.isPartial()) {
            headers.add("X-Partial-Result", "true");
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(CollectionModel.of(airportResources,
                        WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).searchAirports(name, city, country, 0)).withSelfRel()));
    }

    @PatchMapping("/{iataCode}/status")
    public ResponseEntity<EntityModel<AirportViewDTO>> updateStatus(
            @PathVariable String iataCode,
            @RequestBody String newStatus,
            @RequestHeader(value = "X-P2P-Sync", defaultValue = "false") boolean isReplication,
            @RequestHeader(value = "X-Last-Modified", required = false) String lastModified,
            @RequestHeader(value = "X-Origin-Node", required = false) String originNode) {

        boolean authorized = replicationAuthorizer.isAuthorized(isReplication);
        boolean effectiveReplication = isReplication && authorized;

        AirportViewDTO updated = service.updateAirportStatus(iataCode, newStatus.replace("\"", ""), effectiveReplication, parseInstant(lastModified), originNode);

        EntityModel<AirportViewDTO> resource = EntityModel.of(updated);
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(iataCode, 0)).withRel("airport-details"));

        return ResponseEntity.ok(resource);
    }

    @PatchMapping(value = "/{iataCode}/image", consumes = "multipart/form-data")
    public ResponseEntity<EntityModel<AirportViewDTO>> updateAirportImage(
            @PathVariable String iataCode,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestHeader(value = "X-P2P-Sync", defaultValue = "false") boolean isReplication) {

        boolean authorized = replicationAuthorizer.isAuthorized(isReplication);
        boolean effectiveReplication = isReplication && authorized;

        AirportViewDTO updatedAirport = service.updateAirportImage(iataCode, file, effectiveReplication);

        EntityModel<AirportViewDTO> resource = EntityModel.of(updatedAirport);

        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class)
                .updateAirportImage(iataCode, file, false)).withSelfRel());
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class)
                .getAirport(iataCode, 0)).withRel("airport-details"));

        return ResponseEntity.ok(resource);
    }

    @PatchMapping("/{iataCode}/details")
    public ResponseEntity<EntityModel<AirportViewDTO>> updateAirportDetails(
            @PathVariable String iataCode,
            @Valid @RequestBody UpdateAirportDetailsDTO dto,
            @RequestHeader(value = "X-P2P-Sync", defaultValue = "false") boolean isReplication,
            @RequestHeader(value = "X-Last-Modified", required = false) String lastModified,
            @RequestHeader(value = "X-Origin-Node", required = false) String originNode) {

        boolean authorized = replicationAuthorizer.isAuthorized(isReplication);
        boolean effectiveReplication = isReplication && authorized;

        AirportViewDTO updatedAirport = service.updateAirportDetails(iataCode, dto, effectiveReplication, parseInstant(lastModified), originNode);

        EntityModel<AirportViewDTO> resource = EntityModel.of(updatedAirport);
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(iataCode, 0)).withRel("airport-details"));
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).updateAirportDetails(iataCode, dto, false)).withSelfRel());

        return ResponseEntity.ok(resource);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (Exception ignored) { return null; }
    }

    public ResponseEntity<EntityModel<AirportViewDTO>> updateAirportDetails(String iataCode, UpdateAirportDetailsDTO dto, boolean isReplication) {
        return updateAirportDetails(iataCode, dto, isReplication, null, null);
    }

    @GetMapping("/grouped")
    public ResponseEntity<Object> getAirportsGrouped(
            @RequestParam(defaultValue = "country") String by,
            @RequestHeader(value = "X-P2P-Hops", defaultValue = "0") int hops) {

        Object groupedAirports = service.getAirportsGrouped(by, Math.max(0, hops));

        if (((java.util.Map<?, ?>) groupedAirports).isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        HttpHeaders headers = new HttpHeaders();
        if (PartialResultContext.isPartial()) {
            headers.add("X-Partial-Result", "true");
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(groupedAirports);
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<CollectionModel<EntityModel<AirportViewDTO>>> importAirportsFromCsv(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {

        List<AirportViewDTO> importedAirports = service.importAirportsFromCsv(file);

        List<EntityModel<AirportViewDTO>> airportResources = importedAirports.stream()
                .map(dto -> EntityModel.of(dto,
                        WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(dto.iataCode(), 0)).withSelfRel()))
                .collect(Collectors.toList());

        CollectionModel<EntityModel<AirportViewDTO>> collectionModel = CollectionModel.of(airportResources,
                WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).importAirportsFromCsv(file)).withSelfRel());

        return new ResponseEntity<>(collectionModel, HttpStatus.CREATED);
    }
}