package pt.isep.psoft.aisafe.flight_operations.api;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.GroundingRequestDTO;
import pt.isep.psoft.aisafe.flight_operations.application.GroundingService;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightId;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.util.List;

/**
 * Internal API (X-Api-Key, blocked at the gateway). Only local data, never forwards.
 * - GET  /           : snapshot of this replica's command store (bootstrap of an empty replica)
 * - GET  /{id}       : local copy of one flight (diagnostics / tests)
 * - POST /aircraft/{reg}/grounding : kept for compatibility while the Aircraft Service still
 *   calls it by REST; the event-driven path (AircraftStatusChanged) uses the same GroundingService.
 */
@RestController
@RequestMapping("/internal/scheduled-flights")
public class InternalScheduledFlightController {

    private final ScheduledFlightRepository flights;
    private final GroundingService grounding;

    public InternalScheduledFlightController(ScheduledFlightRepository flights, GroundingService grounding) {
        this.flights = flights;
        this.grounding = grounding;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<FlightState> snapshot() {
        return flights.findAll().stream().map(FlightState::of).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<FlightState> getLocal(@PathVariable String id) {
        return flights.findByFlightId_Uuid(FlightId.of(id).value())
                .map(FlightState::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The candidate list sent by the Aircraft Service is ignored: FO chooses from its own references. */
    @PostMapping("/aircraft/{registrationNumber}/grounding")
    public List<String> groundAircraft(@PathVariable String registrationNumber,
                                       @RequestBody(required = false) GroundingRequestDTO ignored,
                                       @RequestHeader(value = ScheduledFlightController.REQUEST_ID, required = false)
                                       String requestId) {
        return grounding.ground(registrationNumber, requestId);
    }
}
