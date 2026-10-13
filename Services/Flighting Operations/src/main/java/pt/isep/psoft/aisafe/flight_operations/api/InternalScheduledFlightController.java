package pt.isep.psoft.aisafe.flight_operations.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.application.ScheduledFlightService;

import java.util.List;

/**
 * PASSO 4 - API INTERNA: so chamada por peers (protegida pelo ApiKeyFilter).
 * Responde APENAS com os dados desta instancia e NUNCA reencaminha -> sem loops.
 * Devolve JSON simples (sem HATEOAS) para ser facil de desserializar.
 */
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

    @GetMapping("/aircraft/{registrationNumber}")
    public List<ScheduledFlightViewDTO> getLocalByAircraft(@PathVariable String registrationNumber) {
        return service.findLocalByAircraft(registrationNumber);
    }

    @GetMapping("/route-utilization")
    public List<RouteUtilizationDTO> getLocalRouteUtilization() {
        return service.findLocalRouteUtilization();
    }
}
