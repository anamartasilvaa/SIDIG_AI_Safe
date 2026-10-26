package pt.isep.psoft.aisafe.aircraft.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.aircraft.application.AircraftService;
import java.util.Map;

@RestController
@RequestMapping("/api/aircraft")
public class AircraftContractController {
    private final AircraftService service;
    public AircraftContractController(AircraftService service) { this.service=service; }
    @GetMapping("/{registrationNumber}")
    public ResponseEntity<Map<String,Object>> get(@PathVariable String registrationNumber) {
        try {
            var dto=service.getAircraftByRegistrationNumber(registrationNumber);
            return ResponseEntity.ok(Map.of("registrationNumber",dto.registrationNumber(),
                    "status",dto.status(),"rangeKm",service.getAircraftRange(registrationNumber),
                    "capacity",dto.seatingCapacity()));
        } catch (IllegalArgumentException e) { return ResponseEntity.notFound().build(); }
    }
}
