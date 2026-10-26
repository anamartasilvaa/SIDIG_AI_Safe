package pt.isep.psoft.aisafe.maintenance.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import pt.isep.psoft.aisafe.maintenance.application.DTO.*;
import pt.isep.psoft.aisafe.maintenance.infrastructure.AircraftClient;
import pt.isep.psoft.aisafe.maintenance.infrastructure.MaintenancePeerClient;
import pt.isep.psoft.aisafe.maintenance.domain.*;
import pt.isep.psoft.aisafe.maintenance.repositories.MaintenancePartRepository;
import pt.isep.psoft.aisafe.maintenance.repositories.MaintenanceRecordRepository;
import pt.isep.psoft.aisafe.maintenance.repositories.MaintenanceTemplateRepository;
import pt.isep.psoft.aisafe.maintenance.infrastructure.MaintenanceReplicator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final MaintenanceTemplateRepository templateRepository;
    private final MaintenanceRecordRepository recordRepository;
    private final MaintenancePartRepository partRepository;
    private final AircraftClient aircraftClient;
    private final MaintenancePeerClient peerClient;
    private final MaintenanceReplicator replicator;

    public MaintenanceService(MaintenanceTemplateRepository templateRepository,
                              MaintenanceRecordRepository recordRepository,
                              MaintenancePartRepository partRepository,
                              AircraftClient aircraftClient,
                              MaintenancePeerClient peerClient,
                              MaintenanceReplicator replicator) {
        this.templateRepository = templateRepository;
        this.recordRepository = recordRepository;
        this.partRepository = partRepository;
        this.aircraftClient = aircraftClient;
        this.peerClient = peerClient;
        this.replicator = replicator;
    }

    @Transactional
    public MaintenanceTemplateViewDTO createTemplate(CreateMaintenanceTemplateDTO dto) {
        audit.info("CREATE_TEMPLATE {}", dto.templateName());
        if (templateRepository.findByTemplateName(dto.templateName()).isPresent()) {
            throw new IllegalArgumentException("Template name already exists: " + dto.templateName());
        }
        List<ChecklistItem> items = dto.checklistItems() == null ? List.of() : dto.checklistItems().stream()
                .map(item -> new ChecklistItem(item.taskDescription(), item.isMandatory()))
                .toList();
        Checklist checklist = new Checklist(dto.checklistTitle(), dto.checklistVersion(), items);
        List<String> modelNames = dto.applicableModelNames() == null ? List.of() : dto.applicableModelNames().stream().map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        MaintenanceTemplate template = new MaintenanceTemplate(dto.templateName(), dto.templateType(), dto.flightHoursInterval(), dto.calendarDaysInterval(), checklist, modelNames);
        return toTemplateView(templateRepository.save(template));
    }

    @Transactional
    public MaintenanceRecordViewDTO registerMaintenance(RegisterMaintenanceDTO dto) {
        String registrationNumber = dto.registrationNumber().trim().toUpperCase();
        String modelName = Optional.ofNullable(dto.modelName()).map(String::trim).filter(s -> !s.isBlank())
                .orElseGet(() -> aircraftClient.getAircraftModelName(registrationNumber));
        audit.info("REGISTER_MAINTENANCE reg={} model={} template={}", registrationNumber, modelName, dto.templateId());

        MaintenanceTemplate template = templateRepository.findById(dto.templateId())
                .orElseThrow(() -> new IllegalArgumentException("Maintenance template not found with ID: " + dto.templateId()));

        LocalDateTime startDate = LocalDateTime.parse(dto.startDate());
        MaintenanceRecord record = new MaintenanceRecord(registrationNumber, modelName, dto.description(), dto.expectedDuration(),
                dto.componentCategory(), startDate, dto.cost(), template.getTemplateName());
        MaintenanceRecord saved = recordRepository.save(record);

        try {
            aircraftClient.updateMaintenanceStatus(registrationNumber, "UNDER_MAINTENANCE");
        } catch (IllegalStateException e) {
            log.warn("Aircraft service update failed, but maintenance record was created: {}", e.getMessage());
        }
        MaintenanceRecordViewDTO view = toRecordView(saved);
        replicator.replicate(view);
        return view;
    }

    @Transactional(readOnly = true)
    public AggregatedResult<MaintenanceRecordViewDTO> getAircraftHistory(String registrationNumber) {
        String normalized = registrationNumber.trim().toUpperCase();
        List<MaintenanceRecordViewDTO> local = recordRepository.findByRegistrationNumberOrderByStartDateDesc(normalized).stream()
                .map(this::toRecordView)
                .toList();
        if (!local.isEmpty()) {
            return new AggregatedResult<>(local, List.of());
        }
        return peerClient.forwardHistory(normalized);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceRecordViewDTO> findLocalHistory(String registrationNumber) {
        return recordRepository.findByRegistrationNumberOrderByStartDateDesc(registrationNumber.trim().toUpperCase()).stream().map(this::toRecordView).toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceRecordViewDTO> findLocalRecords(String registrationNumber, String fromDate,
                                                            String toDate, ComponentCategory category) {
        String normalizedReg = registrationNumber == null || registrationNumber.isBlank()
                ? null : registrationNumber.trim().toUpperCase();
        LocalDateTime start = fromDate == null || fromDate.isBlank()
                ? null : LocalDate.parse(fromDate).atStartOfDay();
        LocalDateTime end = toDate == null || toDate.isBlank()
                ? null : LocalDate.parse(toDate).atTime(23, 59, 59);
        return recordRepository.searchRecords(normalizedReg, start, end, category, Pageable.unpaged())
                .getContent().stream().map(this::toRecordView).toList();
    }

    @Transactional
    public void applyReplica(MaintenanceRecordViewDTO replica) {
        recordRepository.findByGlobalId(replica.globalId()).ifPresentOrElse(local -> {
            if (local.mergeReplica(replica.status(), replica.completionNotes(), replica.completionDate(),
                    replica.revision(), replica.lastModified())) {
                recordRepository.save(local);
                log.info("REPLICA maintenance {} updated to revision {}", replica.globalId(), replica.revision());
            }

        }, () -> {
            MaintenanceRecord created = MaintenanceRecord.fromReplica(
                    replica.globalId(), replica.registrationNumber(), replica.modelName(), replica.templateName(),
                    replica.description(), replica.startDate(), replica.expectedDuration(), replica.completionNotes(),
                    replica.cost(), replica.completionDate(), replica.status(), replica.componentCategory(),
                    replica.revision(), replica.lastModified());
            recordRepository.save(created);
            log.info("REPLICA maintenance {} created at revision {}", replica.globalId(), replica.revision());
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncFromPeersOnStartup() {
        if (!replicator.isEnabled()) {
            return;
        }
        AggregatedResult<MaintenanceRecordViewDTO> remote = peerClient.collectAll();
        if (remote == null) {
            return;
        }
        remote.items().forEach(this::applyReplica);
        log.info("Maintenance startup sync applied {} record(s); unreachable peers: {}",
                remote.items().size(), remote.unreachablePeers());
    }

    @Transactional(readOnly = true)
    public AggregatedResult<MaintenanceRecordViewDTO> searchRecords(String registrationNumber, String fromDate, String toDate, ComponentCategory category) {
        String normalizedReg = registrationNumber == null || registrationNumber.isBlank() ? null : registrationNumber.trim().toUpperCase();
        LocalDateTime start = fromDate != null && !fromDate.isBlank() ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime end = toDate != null && !toDate.isBlank() ? LocalDate.parse(toDate).atTime(23, 59, 59) : null;
        List<MaintenanceRecord> local = recordRepository.searchRecords(normalizedReg, start, end, category, Pageable.unpaged()).getContent();
        if (!local.isEmpty()) {
            return new AggregatedResult<>(local.stream().map(this::toRecordView).toList(), List.of());
        }
        return peerClient.forwardSearch(normalizedReg, category == null ? null : category.name(), fromDate, toDate);
    }

    public Integer getTotalMaintenanceHours() {
        return recordRepository.findAll().stream()
                .filter(record -> record.getStatus() == MaintenanceRecordStatus.COMPLETED)
                .mapToInt(MaintenanceRecord::getExpectedDuration)
                .sum();
    }

    public Integer getTotalMaintenanceHoursByAircraft(String registrationNumber) {
        return recordRepository.findByRegistrationNumberOrderByStartDateDesc(registrationNumber.trim().toUpperCase()).stream()
                .filter(record -> record.getStatus() == MaintenanceRecordStatus.COMPLETED)
                .mapToInt(MaintenanceRecord::getExpectedDuration)
                .sum();
    }

    @Transactional
    public MaintenanceRecordViewDTO completeMaintenance(Long recordId, CompleteMaintenanceDTO dto) {
        MaintenanceRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("Maintenance record not found with ID: " + recordId));
        record.complete(dto.completionNotes());
        MaintenanceRecord saved = recordRepository.save(record);
        try {
            aircraftClient.updateMaintenanceStatus(record.getRegistrationNumber(), "ACTIVE");
        } catch (IllegalStateException e) {
            log.warn("Could not reactivate aircraft {} after maintenance: {}", record.getRegistrationNumber(), e.getMessage());
        }
        audit.info("COMPLETE_MAINTENANCE id={} reg={}", recordId, record.getRegistrationNumber());
        MaintenanceRecordViewDTO view = toRecordView(saved);
        replicator.replicate(view);
        return view;
    }

    public List<MaintenanceRecordViewDTO> getOngoingMaintenances() {
        return recordRepository.findByStatus(MaintenanceRecordStatus.IN_PROGRESS).stream().map(this::toRecordView).toList();
    }

    public List<MaintenanceCostDTO> getMaintenanceCosts(String groupBy) {
        List<MaintenanceRecord> records = recordRepository.findAll();
        Map<String, Double> totals = records.stream()
                .filter(r -> r.getStatus() == MaintenanceRecordStatus.COMPLETED)
                .collect(Collectors.groupingBy(r -> "model".equalsIgnoreCase(groupBy) ? r.getModelName() : r.getRegistrationNumber(),
                        Collectors.summingDouble(MaintenanceRecord::getCost)));
        return totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new MaintenanceCostDTO(e.getKey(), e.getValue()))
                .toList();
    }

    public List<TurnaroundTimeDTO> getTurnaroundTimePerAircraftModel() {
        return recordRepository.findAll().stream()
                .filter(r -> r.getStatus() == MaintenanceRecordStatus.COMPLETED && r.getCompletionDate() != null)
                .collect(Collectors.groupingBy(MaintenanceRecord::getModelName, Collectors.toList())).entrySet().stream()
                .map(entry -> {
                    double average = entry.getValue().stream()
                            .mapToLong(r -> ChronoUnit.HOURS.between(r.getStartDate(), r.getCompletionDate()))
                            .average()
                            .orElse(0.0);
                    return new TurnaroundTimeDTO(entry.getKey(), average);
                })
                .sorted(Comparator.comparingDouble(TurnaroundTimeDTO::averageHours).reversed())
                .toList();
    }

    public List<MaintenanceAlertDTO> generateMaintenanceAlerts() {
        List<MaintenanceAlertDTO> alerts = new ArrayList<>();
        try {
            List<Map<String, Object>> aircrafts = aircraftClient.listAircraftSummaries();
            for (Map<String, Object> aircraft : aircrafts) {
                String reg = String.valueOf(aircraft.getOrDefault("registrationNumber", "")).trim();
                String model = String.valueOf(aircraft.getOrDefault("modelName", aircraft.getOrDefault("model", ""))).trim();
                if (reg.isEmpty()) {
                    continue;
                }
                Optional<MaintenanceRecord> lastCompleted = recordRepository.findTopByRegistrationNumberAndStatusOrderByCompletionDateDesc(reg, MaintenanceRecordStatus.COMPLETED);
                List<MaintenanceTemplate> templates = templateRepository.findByAppliesToModelNamesContaining(model);
                for (MaintenanceTemplate template : templates) {
                    if (template.getCalendarDaysInterval() != null) {
                        LocalDate referenceDate = lastCompleted.isPresent() && lastCompleted.get().getCompletionDate() != null
                                ? lastCompleted.get().getCompletionDate().toLocalDate()
                                : LocalDate.now().minusDays(template.getCalendarDaysInterval());
                        long daysPassed = ChronoUnit.DAYS.between(referenceDate, LocalDate.now());
                        if (daysPassed >= template.getCalendarDaysInterval() - 15) {
                            alerts.add(new MaintenanceAlertDTO(reg, "CALENDAR_DAYS", "Upcoming maintenance warning for " + model));
                        }
                    }
                }
            }
        } catch (IllegalStateException e) {
            log.warn("Aircraft dependency unavailable during alert generation: {}", e.getMessage());
        }
        return alerts;
    }

    public List<LowStockAlertDTO> generateLowStockAlerts() {
        return partRepository.findPartsWithLowStock().stream()
                .map(part -> new LowStockAlertDTO(part.getPartNumber().getNumber(), part.getName(), part.getStockQuantity(), part.getMinimumThreshold(), "CRITICAL"))
                .toList();
    }

    private MaintenanceRecordViewDTO toRecordView(MaintenanceRecord record) {
        return new MaintenanceRecordViewDTO(record.getPk(), record.getRegistrationNumber(), record.getModelName(), record.getTemplateName(),
                record.getDescription(), record.getStartDate(), record.getExpectedDuration(), record.getCompletionNotes(),
                record.getCost(), record.getCompletionDate(), record.getStatus(), record.getComponentCategory(),
                record.getGlobalId(), record.getRevision(), record.getLastModified());
    }

    private MaintenanceTemplateViewDTO toTemplateView(MaintenanceTemplate template) {
        List<ChecklistItemDTO> checklistItems = template.getChecklist() == null ? List.of() : template.getChecklist().getItems().stream()
                .map(item -> new ChecklistItemDTO(item.getTaskDescription(), item.getIsMandatory()))
                .toList();
        return new MaintenanceTemplateViewDTO(template.getPk(), template.getTemplateName(), template.getTemplateType(),
                template.getFlightHoursInterval(), template.getCalendarDaysInterval(),
                template.getChecklist() == null ? null : template.getChecklist().getTitle(),
                template.getChecklist() == null ? null : template.getChecklist().getVersion(),
                checklistItems,
                template.getAppliesToModelNames());
    }
}
