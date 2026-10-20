package pt.isep.psoft.aisafe.airports_routes_service.api;

import jakarta.validation.Valid;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.*;

import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@RequestMapping("/api/airports")
public class AirportController {

    private final AirportService service;

    public AirportController(AirportService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EntityModel<AirportViewDTO>> createAirport(@RequestBody RegisterAirportDTO dto,
                                                                       @RequestHeader(value = "X-P2P-Sync", defaultValue = "false") boolean isReplication) {
        AirportViewDTO created = service.registerAirport(dto, isReplication);

        EntityModel<AirportViewDTO> resource = EntityModel.of(created);
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(created.iataCode())).withSelfRel());

        return new ResponseEntity<>(resource, HttpStatus.CREATED);
    }

    @GetMapping("/{iataCode}")
    public ResponseEntity<EntityModel<AirportViewDTO>> getAirport(
            @PathVariable String iataCode,
            @RequestHeader(value = "X-P2P-Hops", defaultValue = "0") int hops) {
        AirportViewDTO airport = service.getAirportByIataCode(iataCode.toUpperCase(), hops);

        EntityModel<AirportViewDTO> resource = EntityModel.of(airport);

        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(iataCode)).withSelfRel());

        return ResponseEntity.ok(resource);
    }

    public ResponseEntity<EntityModel<AirportViewDTO>> getAirport(String iataCode) {
        return getAirport(iataCode, 0);
    }

    @GetMapping("/search")
    public ResponseEntity<CollectionModel<EntityModel<AirportViewDTO>>> searchAirports(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String country,
            @RequestHeader(value = "X-P2P-Hops", defaultValue = "0") int hops) {

        List<AirportViewDTO> airports = service.searchAirports(name, city, country, hops);

        List<EntityModel<AirportViewDTO>> airportResources = airports.stream()
                .map(dto -> EntityModel.of(dto,
                        WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(dto.iataCode())).withSelfRel()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(CollectionModel.of(airportResources,
                WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).searchAirports(name, city, country)).withSelfRel()));
    }

    public ResponseEntity<CollectionModel<EntityModel<AirportViewDTO>>> searchAirports(
            String name, String city, String country) {
        return searchAirports(name, city, country, 0);
    }
    @PatchMapping("/{iataCode}/status")
    public ResponseEntity<EntityModel<AirportViewDTO>> updateStatus(
            @PathVariable String iataCode,
            @RequestBody String newStatus,
            @RequestHeader(value = "X-P2P-Sync", defaultValue = "false") boolean isReplication) {

        AirportViewDTO updated = service.updateAirportStatus(iataCode, newStatus.replace("\"", ""), isReplication);

        EntityModel<AirportViewDTO> resource = EntityModel.of(updated);
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(iataCode)).withRel("airport-details"));

        return ResponseEntity.ok(resource);
    }

    /* US207 - Upload an image or map of the airport */
    @PatchMapping(value = "/{iataCode}/image", consumes = "multipart/form-data")
    public ResponseEntity<EntityModel<AirportViewDTO>> updateAirportImage(
            @PathVariable String iataCode,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestHeader(value = "X-P2P-Sync", defaultValue = "false") boolean isReplication) {

        AirportViewDTO updatedAirport = service.updateAirportImage(iataCode, file, isReplication);

        EntityModel<AirportViewDTO> resource = EntityModel.of(updatedAirport);

        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class)
                .updateAirportImage(iataCode, file, false)).withSelfRel());
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class)
                .getAirport(iataCode)).withRel("airport-details"));

        return ResponseEntity.ok(resource);
    }

    /* US208 - Update airport details (operational hours and contact) */
    @PatchMapping("/{iataCode}/details")
    public ResponseEntity<EntityModel<AirportViewDTO>> updateAirportDetails(
            @PathVariable String iataCode,
            @Valid @RequestBody UpdateAirportDetailsDTO dto,
            @RequestHeader(value = "X-P2P-Sync", defaultValue = "false") boolean isReplication) {

        AirportViewDTO updatedAirport = service.updateAirportDetails(iataCode, dto, isReplication);

        EntityModel<AirportViewDTO> resource = EntityModel.of(updatedAirport);
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(iataCode)).withRel("airport-details"));
        resource.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).updateAirportDetails(iataCode, dto, false)).withSelfRel());

        return ResponseEntity.ok(resource);
    }

    /* US211 - View airports grouped by region, country, or both */
    @GetMapping("/grouped")
    public ResponseEntity<Object> getAirportsGrouped(
            @RequestParam(defaultValue = "country") String by,
            @RequestHeader(value = "X-P2P-Hops", defaultValue = "0") int hops) {

        Object groupedAirports = service.getAirportsGrouped(by, hops);

        if (((java.util.Map<?, ?>) groupedAirports).isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(groupedAirports);
    }

    /* US225 - Import bulk airport data from CSV files */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<CollectionModel<EntityModel<AirportViewDTO>>> importAirportsFromCsv(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {

        List<AirportViewDTO> importedAirports = service.importAirportsFromCsv(file);

        List<EntityModel<AirportViewDTO>> airportResources = importedAirports.stream()
                .map(dto -> EntityModel.of(dto,
                        WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).getAirport(dto.iataCode())).withSelfRel()))
                .collect(Collectors.toList());

        CollectionModel<EntityModel<AirportViewDTO>> collectionModel = CollectionModel.of(airportResources,
                WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AirportController.class).importAirportsFromCsv(file)).withSelfRel());

        return new ResponseEntity<>(collectionModel, HttpStatus.CREATED);
    }
}