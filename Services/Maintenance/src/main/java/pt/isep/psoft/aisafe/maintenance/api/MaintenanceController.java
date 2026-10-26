package pt.isep.psoft.aisafe.maintenance.api;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.psoft.aisafe.maintenance.application.MaintenanceService;
import pt.isep.psoft.aisafe.maintenance.application.DTO.*;
import pt.isep.psoft.aisafe.maintenance.domain.ComponentCategory;
import pt.isep.psoft.aisafe.maintenance.security.AuditLog;

import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {
    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @PostMapping("/templates")
    public ResponseEntity<EntityModel<MaintenanceTemplateViewDTO>> createTemplate(@Valid @RequestBody CreateMaintenanceTemplateDTO dto) {
        MaintenanceTemplateViewDTO created = maintenanceService.createTemplate(dto);
        AuditLog.record("CREATE_TEMPLATE", dto.templateName());
        EntityModel<MaintenanceTemplateViewDTO> resource = EntityModel.of(created);
        resource.add(linkTo(methodOn(MaintenanceController.class).createTemplate(dto)).withSelfRel());
        return new ResponseEntity<>(resource, HttpStatus.CREATED);
    }

    @PostMapping("/records")
    public ResponseEntity<EntityModel<MaintenanceRecordViewDTO>> registerMaintenance(@Valid @RequestBody RegisterMaintenanceDTO dto) {
        MaintenanceRecordViewDTO record = maintenanceService.registerMaintenance(dto);
        AuditLog.record("REGISTER_MAINTENANCE", dto.registrationNumber());
        EntityModel<MaintenanceRecordViewDTO> resource = EntityModel.of(record);
        resource.add(linkTo(methodOn(MaintenanceController.class).getAircraftHistory(dto.registrationNumber(), null)).withRel("aircraft-history"));
        return new ResponseEntity<>(resource, HttpStatus.CREATED);
    }

    @GetMapping("/records/aircraft/{registrationNumber}")
    public ResponseEntity<CollectionModel<EntityModel<MaintenanceRecordViewDTO>>> getAircraftHistory(
            @PathVariable String registrationNumber, jakarta.servlet.http.HttpServletResponse response) {
        var result = maintenanceService.getAircraftHistory(registrationNumber);
        addPartialResponseHeader(response, result.unreachablePeers());
        List<EntityModel<MaintenanceRecordViewDTO>> records = result.items().stream()
                .map(record -> EntityModel.of(record, linkTo(methodOn(MaintenanceController.class)
                        .getAircraftHistory(registrationNumber, null)).withSelfRel()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(CollectionModel.of(records, linkTo(methodOn(MaintenanceController.class)
                .getAircraftHistory(registrationNumber, null)).withSelfRel()));
    }

    @GetMapping("/records/total-hours")
    public ResponseEntity<EntityModel<java.util.Map<String, Integer>>> getTotalHours() {
        java.util.Map<String, Integer> responseBody = java.util.Map.of("totalHours", maintenanceService.getTotalMaintenanceHours());
        EntityModel<java.util.Map<String, Integer>> resource = EntityModel.of(responseBody);
        resource.add(linkTo(methodOn(MaintenanceController.class).getTotalHours()).withSelfRel());
        return ResponseEntity.ok(resource);
    }

    @GetMapping("/records/aircraft/{registrationNumber}/total-hours")
    public ResponseEntity<EntityModel<java.util.Map<String, Object>>> getTotalHoursByAircraft(@PathVariable String registrationNumber) {
        java.util.Map<String, Object> responseBody = new java.util.HashMap<>();
        responseBody.put("registrationNumber", registrationNumber);
        responseBody.put("totalHours", maintenanceService.getTotalMaintenanceHoursByAircraft(registrationNumber));
        EntityModel<java.util.Map<String, Object>> resource = EntityModel.of(responseBody);
        resource.add(linkTo(methodOn(MaintenanceController.class).getTotalHoursByAircraft(registrationNumber)).withSelfRel());
        return ResponseEntity.ok(resource);
    }

    @PatchMapping("/records/{id}/complete")
    public ResponseEntity<EntityModel<MaintenanceRecordViewDTO>> completeMaintenance(@PathVariable Long id, @Valid @RequestBody CompleteMaintenanceDTO dto) {
        MaintenanceRecordViewDTO record = maintenanceService.completeMaintenance(id, dto);
        AuditLog.record("COMPLETE_MAINTENANCE", String.valueOf(id));
        EntityModel<MaintenanceRecordViewDTO> resource = EntityModel.of(record);
        resource.add(linkTo(methodOn(MaintenanceController.class).getTotalHours()).withRel("view-fleet-total-hours"));
        return ResponseEntity.ok(resource);
    }

    @GetMapping("/records/search")
    public ResponseEntity<PagedModel<EntityModel<MaintenanceRecordViewDTO>>> searchRecords(
            @RequestParam(required = false) String registrationNumber,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false, name = "componentCategory") String category,
            Pageable pageable,
            jakarta.servlet.http.HttpServletResponse response,
            PagedResourcesAssembler<MaintenanceRecordViewDTO> assembler) {
        ComponentCategory categoryEnum = null;
        if (category != null && !category.isBlank()) {
            try {
                categoryEnum = ComponentCategory.valueOf(category.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        var result = maintenanceService.searchRecords(registrationNumber, fromDate, toDate, categoryEnum);
        addPartialResponseHeader(response, result.unreachablePeers());
        List<MaintenanceRecordViewDTO> records = result.items();
        Page<MaintenanceRecordViewDTO> page = new org.springframework.data.domain.PageImpl<>(records, pageable, records.size());
        PagedModel<EntityModel<MaintenanceRecordViewDTO>> pagedModel = assembler.toModel(page, record -> EntityModel.of(record));
        return ResponseEntity.ok(pagedModel);
    }

    private void addPartialResponseHeader(jakarta.servlet.http.HttpServletResponse response,
                                          List<String> unreachablePeers) {
        if (!unreachablePeers.isEmpty()) {
            response.setHeader("X-Partial-Response-Unreachable-Peers", String.join(",", unreachablePeers));
        }
    }

    @GetMapping("/records/ongoing")
    public ResponseEntity<CollectionModel<EntityModel<MaintenanceRecordViewDTO>>> getOngoingMaintenances() {
        List<EntityModel<MaintenanceRecordViewDTO>> records = maintenanceService.getOngoingMaintenances().stream()
                .map(r -> EntityModel.of(r, linkTo(methodOn(MaintenanceController.class).getOngoingMaintenances()).withSelfRel()))
                .toList();
        return ResponseEntity.ok(CollectionModel.of(records, linkTo(methodOn(MaintenanceController.class).getOngoingMaintenances()).withSelfRel()));
    }

    @GetMapping("/statistics/costs")
    public ResponseEntity<CollectionModel<EntityModel<MaintenanceCostDTO>>> getMaintenanceCosts(@RequestParam(required = false) String groupBy) {
        List<MaintenanceCostDTO> costs = maintenanceService.getMaintenanceCosts(groupBy);
        if (costs.isEmpty()) return ResponseEntity.noContent().build();
        List<EntityModel<MaintenanceCostDTO>> resources = costs.stream().map(EntityModel::of).collect(Collectors.toList());
        return ResponseEntity.ok(CollectionModel.of(resources, linkTo(methodOn(MaintenanceController.class).getMaintenanceCosts(groupBy)).withSelfRel()));
    }

    @GetMapping("/statistics/turnaround")
    public ResponseEntity<CollectionModel<EntityModel<TurnaroundTimeDTO>>> getTurnaroundTime() {
        List<TurnaroundTimeDTO> turnaroundStats = maintenanceService.getTurnaroundTimePerAircraftModel();
        if (turnaroundStats.isEmpty()) return ResponseEntity.noContent().build();
        List<EntityModel<TurnaroundTimeDTO>> resources = turnaroundStats.stream().map(EntityModel::of).collect(Collectors.toList());
        return ResponseEntity.ok(CollectionModel.of(resources, linkTo(methodOn(MaintenanceController.class).getTurnaroundTime()).withSelfRel()));
    }

    @GetMapping("/alerts")
    public ResponseEntity<CollectionModel<EntityModel<MaintenanceAlertDTO>>> getMaintenanceAlerts(@RequestParam(required = false) String type) {
        List<MaintenanceAlertDTO> alerts = maintenanceService.generateMaintenanceAlerts();
        if (type != null && !type.isEmpty()) {
            alerts = alerts.stream().filter(a -> a.alertType().equalsIgnoreCase(type)).collect(Collectors.toList());
        }
        if (alerts.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(CollectionModel.of(alerts.stream().map(EntityModel::of).toList(),
                linkTo(methodOn(MaintenanceController.class).getMaintenanceAlerts(type)).withSelfRel()));
    }

    @GetMapping("/parts/low-stock-alerts")
    public ResponseEntity<CollectionModel<EntityModel<LowStockAlertDTO>>> getLowStockAlerts() {
        List<LowStockAlertDTO> alerts = maintenanceService.generateLowStockAlerts();
        if (alerts.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(CollectionModel.of(alerts.stream().map(EntityModel::of).toList(),
                linkTo(methodOn(MaintenanceController.class).getLowStockAlerts()).withSelfRel()));
    }
}
