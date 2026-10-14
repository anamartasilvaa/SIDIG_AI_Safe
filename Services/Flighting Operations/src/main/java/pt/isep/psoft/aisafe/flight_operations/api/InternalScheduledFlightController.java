package pt.isep.psoft.aisafe.flight_operations.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ChangeFlightStatusDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.application.ScheduledFlightService;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;

import java.util.List;

@RestController
@RequestMapping("/internal/scheduled-flights")
public class InternalScheduledFlightController {

    private final ScheduledFlightService service;

    public InternalScheduledFlightController(ScheduledFlightService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScheduledFlightViewDTO> getLocal(@PathVariable String id) {
        return service.findLocalById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ScheduledFlightViewDTO> changeLocalStatus(@PathVariable String id,
                                                                    @Valid @RequestBody ChangeFlightStatusDTO dto) {
        return service.changeLocalStatus(id, FlightStatus.parse(dto.status()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/aircraft/{registrationNumber}")
    public List<ScheduledFlightViewDTO> getLocalByAircraft(@PathVariable String registrationNumber) {
        return service.findLocalByAircraft(registrationNumber);
    }

    @GetMapping("/route-utilization")
    public List<RouteUtilizationDTO> getLocalRouteUtilization() {
        return service.findLocalRouteUtilization();
    }
}