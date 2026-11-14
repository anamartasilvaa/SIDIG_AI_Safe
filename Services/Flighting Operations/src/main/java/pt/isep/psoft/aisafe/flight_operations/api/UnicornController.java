package pt.isep.psoft.aisafe.flight_operations.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.UnicornDTO;
import pt.isep.psoft.aisafe.flight_operations.application.query.FlightQueryService;

import java.util.List;

/**
 * API v2 (URI versioning): mood status of the aircraft. Pure CQRS query: there is no command
 * for the mood; it is a read model derived from flight and aircraft events.
 */
@RestController
@RequestMapping("/api/v2/unicorns")
public class UnicornController {

    private final FlightQueryService queries;

    public UnicornController(FlightQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public ResponseEntity<List<UnicornDTO>> unicorns(@RequestParam(required = false) String mood) {
        return ResponseEntity.ok(queries.unicorns(mood));
    }
}
