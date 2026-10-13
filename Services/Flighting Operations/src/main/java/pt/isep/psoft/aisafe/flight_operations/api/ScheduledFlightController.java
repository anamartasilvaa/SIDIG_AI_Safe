package pt.isep.psoft.aisafe.flight_operations.api;

import jakarta.validation.Valid;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.application.ScheduledFlightService;

import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/** API PUBLICA: usada pelos clientes. As leituras sao distribuidas (local + peers). */
@RestController
@RequestMapping("/api/scheduled-flights")
public class ScheduledFlightController {

    /** Presente quando algum peer nao respondeu: o cliente sabe que a resposta e parcial. */
    public static final String PARTIAL_HEADER = "X-Partial-Response-Unreachable-Peers";

    private final ScheduledFlightService service;

    public ScheduledFlightController(ScheduledFlightService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EntityModel<ScheduledFlightViewDTO>> scheduleFlight(
            @Valid @RequestBody CreateScheduledFlightDTO dto) {
        ScheduledFlightViewDTO created = service.scheduleFlight(dto);
        EntityModel<ScheduledFlightViewDTO> resource = EntityModel.of(created,
                linkTo(methodOn(ScheduledFlightController.class).getScheduledFlightById(created.id())).withSelfRel(),
                linkTo(methodOn(ScheduledFlightController.class)
                        .getFlightsByAircraft(created.registrationNumber())).withRel("aircraft-flights"));
        return ResponseEntity
                .created(linkTo(methodOn(ScheduledFlightController.class).getScheduledFlightById(created.id())).toUri())
                .body(resource);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EntityModel<ScheduledFlightViewDTO>> getScheduledFlightById(@PathVariable String id) {
        return service.findById(id)
                .map(f -> EntityModel.of(f,
                        linkTo(methodOn(ScheduledFlightController.class).getScheduledFlightById(id)).withSelfRel()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/aircraft/{registrationNumber}")
    public ResponseEntity<CollectionModel<EntityModel<ScheduledFlightViewDTO>>> getFlightsByAircraft(
            @PathVariable String registrationNumber) {
        AggregatedResult<ScheduledFlightViewDTO> result = service.findByAircraft(registrationNumber);
        if (result.items().isEmpty()) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).headers(partialHeaders(result)).build();
        }
        List<EntityModel<ScheduledFlightViewDTO>> resources = result.items().stream()
                .map(f -> EntityModel.of(f,
                        linkTo(methodOn(ScheduledFlightController.class).getScheduledFlightById(f.id())).withSelfRel()))
                .toList();
        return ResponseEntity.ok().headers(partialHeaders(result)).body(CollectionModel.of(resources,
                linkTo(methodOn(ScheduledFlightController.class).getFlightsByAircraft(registrationNumber)).withSelfRel()));
    }

    /* US229 - relatorio de utilizacao das rotas, agregado de todas as replicas */
    @GetMapping("/route-utilization")
    public ResponseEntity<List<RouteUtilizationDTO>> getRouteUtilizationReport() {
        AggregatedResult<RouteUtilizationDTO> result = service.routeUtilization();
        if (result.items().isEmpty()) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).headers(partialHeaders(result)).build();
        }
        return ResponseEntity.ok().headers(partialHeaders(result)).body(result.items());
    }

    private static HttpHeaders partialHeaders(AggregatedResult<?> result) {
        HttpHeaders headers = new HttpHeaders();
        if (result.isPartial()) {
            headers.add(PARTIAL_HEADER, String.join(",", result.unreachablePeers()));
        }
        return headers;
    }
}
