package pt.isep.psoft.aisafe.flight_operations.api;

import jakarta.validation.Valid;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AvailabilityDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ChangeFlightStatusDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.application.FlightCommandService;
import pt.isep.psoft.aisafe.flight_operations.application.query.FlightQueryService;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;
import pt.isep.psoft.aisafe.flight_operations.security.AuditLog;

import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Public REST API (unchanged URLs). CQRS: POST/PATCH go to the command service,
 * GET go to the query service (read model).
 */
@RestController
@RequestMapping("/api/scheduled-flights")
public class ScheduledFlightController {

    static final String REQUEST_ID = "X-Request-Id";

    private final FlightCommandService commands;
    private final FlightQueryService queries;

    public ScheduledFlightController(FlightCommandService commands, FlightQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    // ------------------------------------------------------------------ commands

    @PostMapping
    public ResponseEntity<EntityModel<ScheduledFlightViewDTO>> scheduleFlight(
            @Valid @RequestBody CreateScheduledFlightDTO dto,
            @RequestHeader(value = REQUEST_ID, required = false) String requestId) {
        ScheduledFlight created = commands.schedule(dto, requestId);
        ScheduledFlightViewDTO body = ScheduledFlightViewDTO.from(created);
        AuditLog.record("CREATE", body.id());
        return ResponseEntity
                .created(linkTo(methodOn(ScheduledFlightController.class).getScheduledFlightById(body.id())).toUri())
                .body(withLinks(body));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<EntityModel<ScheduledFlightViewDTO>> changeStatus(
            @PathVariable String id, @Valid @RequestBody ChangeFlightStatusDTO dto,
            @RequestHeader(value = REQUEST_ID, required = false) String requestId) {
        return commands.changeStatus(id, dto.status(), requestId)
                .map(ScheduledFlightViewDTO::from)
                .map(f -> {
                    AuditLog.record("UPDATE_STATUS " + f.status(), f.id());
                    return ResponseEntity.ok(withLinks(f));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------- queries

    @GetMapping("/{id}")
    public ResponseEntity<EntityModel<ScheduledFlightViewDTO>> getScheduledFlightById(@PathVariable String id) {
        return queries.findById(id)
                .map(f -> ResponseEntity.ok().eTag(String.valueOf(f.revision())).body(withLinks(f)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/aircraft/{registrationNumber}")
    public ResponseEntity<CollectionModel<EntityModel<ScheduledFlightViewDTO>>> getFlightsByAircraft(
            @PathVariable String registrationNumber) {
        List<ScheduledFlightViewDTO> flights = queries.findByAircraft(registrationNumber);
        if (flights.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        return ResponseEntity.ok(CollectionModel.of(flights.stream().map(this::withLinks).toList(),
                linkTo(methodOn(ScheduledFlightController.class).getFlightsByAircraft(registrationNumber)).withSelfRel()));
    }

    @GetMapping("/availability")
    public ResponseEntity<AvailabilityDTO> checkAvailability(@RequestParam String registrationNumber,
                                                             @RequestParam String dateTime) {
        return ResponseEntity.ok(queries.availability(registrationNumber, dateTime));
    }

    /** US229: most flown routes (read model, pre-computed). */
    @GetMapping("/route-utilization")
    public ResponseEntity<List<RouteUtilizationDTO>> getRouteUtilizationReport() {
        List<RouteUtilizationDTO> report = queries.routeUtilization();
        return report.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(report);
    }

    private EntityModel<ScheduledFlightViewDTO> withLinks(ScheduledFlightViewDTO f) {
        return EntityModel.of(f,
                linkTo(methodOn(ScheduledFlightController.class).getScheduledFlightById(f.id())).withSelfRel(),
                linkTo(methodOn(ScheduledFlightController.class).getFlightsByAircraft(f.registrationNumber()))
                        .withRel("aircraft-flights"));
    }
}
