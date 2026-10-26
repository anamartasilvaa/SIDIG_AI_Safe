package pt.isep.psoft.aisafe.aircraft.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.aircraft.application.AircraftService;
import pt.isep.psoft.aisafe.aircraft.application.DTO.AircraftViewDTO;
import pt.isep.psoft.aisafe.aircraft.application.DTO.UpdateAircraftStatusDTO;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/internal/aircraft")
public class InternalAircraftController {
    private final AircraftService service;
    public InternalAircraftController(AircraftService service) { this.service=service; }
    @GetMapping("/instances/{registrationNumber}")
    public ResponseEntity<AircraftViewDTO> getLocal(@PathVariable String registrationNumber) {
        return service.findLocalAircraft(registrationNumber).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
    @GetMapping("/instances")
    public List<AircraftViewDTO> searchLocal(@RequestParam(required=false) String model,
                                              @RequestParam(required=false) String status,
                                              @RequestParam(required=false) Integer year) {
        return service.searchLocalAircrafts(model,status,year);
    }
    @PatchMapping("/instances/{registrationNumber}/maintenance-status")
    public ResponseEntity<Map<String,Object>> maintenanceStatus(@PathVariable String registrationNumber,
                                                                  @RequestBody UpdateAircraftStatusDTO dto) {
        return ResponseEntity.ok(service.updateAircraftStatusWithReport(registrationNumber, dto));
    }

    @PatchMapping("/instances/{registrationNumber}/increment-assignments")
    public ResponseEntity<Void> incrementAssignments(@PathVariable String registrationNumber) {
        service.incrementAircraftAssignments(registrationNumber);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/instances/{registrationNumber}/replica")
    public ResponseEntity<Void> receiveReplica(@PathVariable String registrationNumber,
                                                @RequestBody AircraftViewDTO replica) {
        if (!registrationNumber.equalsIgnoreCase(replica.registrationNumber()))
            throw new IllegalArgumentException("Path registration and body registration differ.");
        service.applyReplica(replica);
        return ResponseEntity.noContent().build();
    }
}
