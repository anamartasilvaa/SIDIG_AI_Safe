package pt.isep.psoft.aisafe.maintenance.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.maintenance.application.MaintenanceService;
import pt.isep.psoft.aisafe.maintenance.application.DTO.MaintenanceRecordViewDTO;
import pt.isep.psoft.aisafe.maintenance.application.DTO.MaintenanceTemplateViewDTO;

import java.util.List;

@RestController
@RequestMapping("/internal/maintenance")
public class InternalMaintenanceController {
    private final MaintenanceService maintenanceService;

    public InternalMaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @GetMapping("/records/{registrationNumber}")
    public List<MaintenanceRecordViewDTO> lookupRecord(@PathVariable String registrationNumber) {
        return maintenanceService.findLocalHistory(registrationNumber);
    }

    @GetMapping("/records")
    public List<MaintenanceRecordViewDTO> searchLocal(@RequestParam(required = false) String registrationNumber,
                                                    @RequestParam(required = false) String category,
                                                    @RequestParam(required = false) String fromDate,
                                                    @RequestParam(required = false) String toDate) {
        return maintenanceService.findLocalRecords(registrationNumber, fromDate, toDate,
                category == null || category.isBlank() ? null :
                        pt.isep.psoft.aisafe.maintenance.domain.ComponentCategory.valueOf(category.toUpperCase()))
                ;
    }

    @PutMapping("/records/{globalId}/replica")
    public ResponseEntity<Void> receiveReplica(@PathVariable String globalId,
                                                @RequestBody MaintenanceRecordViewDTO replica) {
        if (!globalId.equals(replica.globalId())) throw new IllegalArgumentException("Replica id mismatch");
        maintenanceService.applyReplica(replica);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/templates")
    public List<MaintenanceTemplateViewDTO> searchLocalTemplates() {
        return maintenanceService.findLocalTemplates();
    }

    @PutMapping("/templates/{globalId}/replica")
    public ResponseEntity<Void> receiveTemplateReplica(@PathVariable String globalId,
                                                       @RequestBody MaintenanceTemplateViewDTO replica) {
        if (!globalId.equals(replica.globalId())) throw new IllegalArgumentException("Template replica id mismatch");
        maintenanceService.applyTemplateReplica(replica);
        return ResponseEntity.noContent().build();
    }
}
