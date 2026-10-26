package pt.isep.psoft.aisafe.aircraft.api;

import jakarta.validation.Valid;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pt.isep.psoft.aisafe.aircraft.application.AircraftService;
import pt.isep.psoft.aisafe.aircraft.application.DTO.*;
import pt.isep.psoft.aisafe.aircraft.security.AuditLog;

import java.util.List;
import java.util.Map;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@RequestMapping("/api/aircraft-models")
public class AircraftController {
    public static final String PARTIAL_HEADER = "X-Partial-Response-Unreachable-Peers";

    private final AircraftService service;

    public AircraftController(AircraftService service) {
        this.service = service;
    }


    /*US101 - Register an aircraft model*/
    @PostMapping
    public ResponseEntity<EntityModel<RegisterAircraftModelDTO>> createModel(@Valid @RequestBody RegisterAircraftModelDTO dto) {
        service.registerModel(dto);
        AuditLog.record("CREATE_MODEL", dto.modelName());
        EntityModel<RegisterAircraftModelDTO> resource = EntityModel.of(dto);
        resource.add(linkTo(methodOn(AircraftController.class).createModel(dto)).withSelfRel());
        return new ResponseEntity<>(resource, HttpStatus.CREATED);
    }

    /*US102 - Register a specific aircraft instance*/
    @PostMapping("/instances")
    public ResponseEntity<EntityModel<RegisterAircraftDTO>> createAircraft(@Valid @RequestBody RegisterAircraftDTO dto) {
        service.registerAircraft(dto);
        AuditLog.record("CREATE_AIRCRAFT", dto.registrationNumber());
        EntityModel<RegisterAircraftDTO> resource = EntityModel.of(dto);
        resource.add(linkTo(methodOn(AircraftController.class).createAircraft(dto)).withSelfRel());
        return new ResponseEntity<>(resource, HttpStatus.CREATED);
    }

    /* US103 - View aircraft details by registration number (MODIFIED for distributed lookup) */
    @GetMapping("/instances/{registrationNumber}")
    public ResponseEntity<EntityModel<AircraftViewDTO>> getAircraft(
            @PathVariable String registrationNumber,
            @RequestHeader(value = "X-Peer-Request", defaultValue = "false") boolean isPeerRequest) {

        try {
            AircraftViewDTO dto = service.getAircraftByRegistrationNumber(registrationNumber, isPeerRequest);
        EntityModel<AircraftViewDTO> resource = EntityModel.of(dto);
            // Re-construct the self-link maintaining the peer request state
            resource.add(linkTo(methodOn(AircraftController.class).getAircraft(registrationNumber, isPeerRequest)).withSelfRel());
        return ResponseEntity.ok(resource);
        } catch (IllegalArgumentException e) {
            // Return 404 so peer clients know the aircraft wasn't found here
            // This is crucial for the resilient RestTemplate to function properly without tripping circuit breakers
            return ResponseEntity.notFound().build();
        }
    }

    /* US104 - Search aircraft (MODIFIED for eventual distributed search) */
    @GetMapping("/instances")
    public ResponseEntity<CollectionModel<EntityModel<AircraftViewDTO>>> searchAircrafts(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer year) {

        // The service merges local results with peer results and removes duplicates.
        AggregatedResult<AircraftViewDTO> result = service.searchAircrafts(model, status, year);
        List<EntityModel<AircraftViewDTO>> resources = result.items().stream()
                .map(dto -> EntityModel.of(dto,
                        // Self links for individual resources default to false for peer request
                        linkTo(methodOn(AircraftController.class).getAircraft(dto.registrationNumber(), false)).withSelfRel()))
                .toList();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.isPartial()) {
            response.header(PARTIAL_HEADER, String.join(",", result.unreachablePeers()));
        }
        return response.body(CollectionModel.of(resources,
                linkTo(methodOn(AircraftController.class).searchAircrafts(model, status, year)).withSelfRel()));
    }

    /*US105 + US222/US119 - Update an aircraft operational status & Trigger Swap Algorithm*/
    @PatchMapping("/instances/{registrationNumber}/status")
    public ResponseEntity<EntityModel<Map<String, Object>>> updateStatus(
            @PathVariable String registrationNumber,
            @Valid @RequestBody UpdateAircraftStatusDTO dto) {

        Map<String, Object> responseMap = service.updateAircraftStatusWithReport(registrationNumber, dto);
        AuditLog.record("UPDATE_STATUS " + dto.status(), registrationNumber);
        EntityModel<Map<String, Object>> resource = EntityModel.of(responseMap);
        resource.add(linkTo(methodOn(AircraftController.class).getAircraft(registrationNumber, false)).withSelfRel());

        return ResponseEntity.ok(resource);
    }

    /* US201  */
    @PatchMapping("/models/{modelName}/specifications")
    public ResponseEntity<EntityModel<AircraftModelViewDTO>> updateModelSpecifications(
            @PathVariable String modelName,
            @Valid @RequestBody UpdateAircraftModelSpecsDTO dto) {

        AircraftModelViewDTO updatedModel = service.updateModelSpecifications(modelName, dto);
        AuditLog.record("UPDATE_MODEL", modelName);
        EntityModel<AircraftModelViewDTO> resource = EntityModel.of(updatedModel);
        resource.add(linkTo(methodOn(AircraftController.class)
                .updateModelSpecifications(modelName, dto)).withSelfRel());

        return ResponseEntity.ok(resource);
    }

    /* US202 -*/
    @PatchMapping(value = "/models/{modelName}/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EntityModel<AircraftModelViewDTO>> updateModelImage(
            @PathVariable String modelName,
            @RequestPart("image") MultipartFile file) {
        AircraftModelViewDTO updatedModel = service.updateModelImage(modelName, file);
        AuditLog.record("UPDATE_MODEL_IMAGE", modelName);

        EntityModel<AircraftModelViewDTO> resource = EntityModel.of(updatedModel);

        resource.add(linkTo(methodOn(AircraftController.class).updateModelImage(modelName, null)).withSelfRel());

        return ResponseEntity.ok(resource);
    }

    /* US204 - Top 5 by MODEL */
    @io.swagger.v3.oas.annotations.Operation(summary = "Get Top 5 Aircraft Models", description = "Get the top 5 most utilized aircraft models based on total flight hours or number of assignments.")
    @GetMapping("/models/top5")
    public ResponseEntity<CollectionModel<EntityModel<TopAircraftModelDTO>>> getTop5Models(
            @RequestParam(defaultValue = "hours") String sortBy) {

        List<TopAircraftModelDTO> top5 = service.getTop5UtilizedModels(sortBy);

        List<EntityModel<TopAircraftModelDTO>> resources = top5.stream()
                .map(EntityModel::of)
                .toList();

        return ResponseEntity.ok(CollectionModel.of(resources,
                linkTo(methodOn(AircraftController.class).getTop5Models(sortBy)).withSelfRel()));
    }

    /* US223 - Top 5 by INDIVIDUAL AIRCRAFT */
    @io.swagger.v3.oas.annotations.Operation(summary = "Get Top 5 Aircraft Instances", description = "Get the top 5 most utilized aircraft instances based on assignments or hours.")
    @GetMapping("/instances/top5")
    public ResponseEntity<CollectionModel<EntityModel<TopAircraftModelDTO>>> getTop5AircraftInstances(
            @RequestParam(defaultValue = "assignments") String sortBy) {

        List<TopAircraftModelDTO> top5 = service.getTop5UtilizedAircrafts(sortBy);

        List<EntityModel<TopAircraftModelDTO>> resources = top5.stream()
                .map(EntityModel::of)
                .toList();

        return ResponseEntity.ok(CollectionModel.of(resources,
                linkTo(methodOn(AircraftController.class).getTop5AircraftInstances(sortBy)).withSelfRel()));
    }

    /* US206  */
    @GetMapping("/instances/{registrationNumber}/operational-hours")
    public ResponseEntity<EntityModel<OperationalHoursDTO>> getOperationalHours(
            @PathVariable String registrationNumber) {

        OperationalHoursDTO dto = service.getAircraftOperationalHours(registrationNumber);

        EntityModel<OperationalHoursDTO> resource = EntityModel.of(dto);
        resource.add(linkTo(methodOn(AircraftController.class).getOperationalHours(registrationNumber)).withSelfRel());

        return ResponseEntity.ok(resource);
    }

    /* US206  */
    @GetMapping("/instances/operational-hours")
    public ResponseEntity<org.springframework.hateoas.PagedModel<EntityModel<OperationalHoursDTO>>> getAllOperationalHours(
            org.springframework.data.domain.Pageable pageable,
            org.springframework.data.web.PagedResourcesAssembler<OperationalHoursDTO> assembler) {

        org.springframework.data.domain.Page<OperationalHoursDTO> page = service.getAllAircraftOperationalHours(pageable);

        org.springframework.hateoas.PagedModel<EntityModel<OperationalHoursDTO>> pagedModel =
                assembler.toModel(page, dto -> EntityModel.of(dto,
                        linkTo(methodOn(AircraftController.class).getOperationalHours(dto.registrationNumber())).withSelfRel()));

        return ResponseEntity.ok(pagedModel);
    }

    /* US203*/
    @GetMapping("/instances/{registrationNumber}/compatible-routes")
    public ResponseEntity<org.springframework.hateoas.PagedModel<EntityModel<RouteViewDTO>>> getCompatibleRoutes(
            @PathVariable String registrationNumber,
            org.springframework.data.domain.Pageable pageable,
            org.springframework.data.web.PagedResourcesAssembler<RouteViewDTO> assembler) {

        org.springframework.data.domain.Page<RouteViewDTO> compatibleRoutes =
                service.getCompatibleRoutesForAircraft(registrationNumber, pageable);
        org.springframework.hateoas.PagedModel<EntityModel<RouteViewDTO>> pagedModel =
                assembler.toModel(compatibleRoutes, EntityModel::of);

        return ResponseEntity.ok(pagedModel);
    }

    // US205
    @GetMapping("/instances/{registrationNumber}/real-time-status")
    public ResponseEntity<EntityModel<RealTimeStatusDTO>> getRealTimeStatus(@PathVariable String registrationNumber) {

        String status = service.getRealTimeAircraftStatus(registrationNumber);

        RealTimeStatusDTO responseDto = new RealTimeStatusDTO(registrationNumber, status);

        EntityModel<RealTimeStatusDTO> resource = EntityModel.of(responseDto);
        resource.add(linkTo(methodOn(AircraftController.class).getRealTimeStatus(registrationNumber)).withSelfRel());

        return ResponseEntity.ok(resource);
    }

    /* US224 - Search for aircraft by specific features (e.g., Wi-Fi, engine type) */
    @GetMapping("/instances/search-features")
    public ResponseEntity<CollectionModel<EntityModel<AircraftViewDTO>>> searchAircraftByFeatures(
            @RequestParam(required = false) String feature,
            @RequestParam(required = false) String engineType) {

        List<AircraftViewDTO> list = service.searchAircraftByFeatures(feature, engineType);

        if (list.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        List<EntityModel<AircraftViewDTO>> resources = list.stream()
                .map(dto -> EntityModel.of(dto,
                        linkTo(methodOn(AircraftController.class).getAircraft(dto.registrationNumber(), false)).withSelfRel()))
                .toList();

        return ResponseEntity.ok(CollectionModel.of(resources,
                linkTo(methodOn(AircraftController.class).searchAircraftByFeatures(feature, engineType)).withSelfRel()));
    }

    /* US227 - Fuel efficiency metrics */
    @GetMapping("/fuel-efficiency")
    public ResponseEntity<?> getFuelEfficiency(@RequestParam String regNum) {

        var metrics = Map.of("efficiencyMetric", 85.5, "registrationNumber", regNum);

        return ResponseEntity.ok(metrics);
    }
}
