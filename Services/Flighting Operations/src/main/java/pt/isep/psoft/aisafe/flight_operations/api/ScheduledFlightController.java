package pt.isep.psoft.aisafe.flight_operations.api;

import jakarta.validation.Valid;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AvailabilityDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ChangeFlightStatusDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduleResultDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.application.ScheduledFlightService;

import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@RequestMapping("/api/scheduled-flights")
public class ScheduledFlightController {

    public static final String PARTIAL_HEADER = "X-Partial-Response-Unreachable-Peers";

    private final ScheduledFlightService service;

    public ScheduledFlightController(ScheduledFlightService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EntityModel<ScheduledFlightViewDTO>> scheduleFlight(
            @Valid @RequestBody CreateScheduledFlightDTO dto) {
        ScheduleResultDTO result = service.scheduleFlight(dto);
        ScheduledFlightViewDTO created = result.flight();
        HttpHeaders headers = new HttpHeaders();
        if (result.isPartial()) {
            headers.add(PARTIAL_HEADER, String.join(",", result.unreachablePeers()));
        }
        return ResponseEntity
                .created(linkTo(methodOn(ScheduledFlightController.class).getScheduledFlightById(created.id())).toUri())
                .headers(headers)
                .body(withLinks(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EntityModel<ScheduledFlightViewDTO>> getScheduledFlightById(@PathVariable String id) {
        return service.findById(id)
                .map(f -> ResponseEntity.ok(withLinks(f)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Muda o estado do voo (ex.: DELAYED, IN_FLIGHT, COMPLETED, CANCELED), em qualquer replica. */
    @PatchMapping("/{id}/status")
    public ResponseEntity<EntityModel<ScheduledFlightViewDTO>> changeStatus(
            @PathVariable String id, @Valid @RequestBody ChangeFlightStatusDTO dto) {
        return service.changeStatus(id, dto.status())
                .map(f -> ResponseEntity.ok(withLinks(f)))
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
                .map(this::withLinks)
                .toList();
        return ResponseEntity.ok().headers(partialHeaders(result)).body(CollectionModel.of(resources,
                linkTo(methodOn(ScheduledFlightController.class).getFlightsByAircraft(registrationNumber)).withSelfRel()));
    }


    @GetMapping("/availability")
    public ResponseEntity<AvailabilityDTO> checkAvailability(@RequestParam String registrationNumber,
                                                             @RequestParam String dateTime) {
        AvailabilityDTO result = service.checkAvailability(registrationNumber, dateTime);
        HttpHeaders headers = new HttpHeaders();
        if (!result.unreachablePeers().isEmpty()) {
            headers.add(PARTIAL_HEADER, String.join(",", result.unreachablePeers()));
        }
        return ResponseEntity.ok().headers(headers).body(result);
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

    private EntityModel<ScheduledFlightViewDTO> withLinks(ScheduledFlightViewDTO f) {
        return EntityModel.of(f,
                linkTo(methodOn(ScheduledFlightController.class).getScheduledFlightById(f.id())).withSelfRel(),
                linkTo(methodOn(ScheduledFlightController.class).getFlightsByAircraft(f.registrationNumber()))
                        .withRel("aircraft-flights"));
    }

    private static HttpHeaders partialHeaders(AggregatedResult<?> result) {
        HttpHeaders headers = new HttpHeaders();
        if (result.isPartial()) {
            headers.add(PARTIAL_HEADER, String.join(",", result.unreachablePeers()));
        }
        return headers;
    }
}
