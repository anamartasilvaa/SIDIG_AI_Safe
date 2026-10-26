package pt.isep.psoft.aisafe.maintenance.application.DTO;

import pt.isep.psoft.aisafe.maintenance.domain.ComponentCategory;
import pt.isep.psoft.aisafe.maintenance.domain.MaintenanceRecordStatus;

import java.time.LocalDateTime;
import java.time.Instant;

public record MaintenanceRecordViewDTO(
        Long pk,
        String registrationNumber,
        String modelName,
        String templateName,
        String description,
        LocalDateTime startDate,
        Integer expectedDuration,
        String completionNotes,
        Double cost,
        LocalDateTime completionDate,
        MaintenanceRecordStatus status,
        ComponentCategory componentCategory,
        String globalId,
        long revision,
        Instant lastModified) {
    public MaintenanceRecordViewDTO(Long pk, String registrationNumber, String modelName, String templateName,
                                    String description, LocalDateTime startDate, Integer expectedDuration,
                                    String completionNotes, Double cost, LocalDateTime completionDate,
                                    MaintenanceRecordStatus status, ComponentCategory componentCategory) {
        this(pk, registrationNumber, modelName, templateName, description, startDate, expectedDuration,
                completionNotes, cost, completionDate, status, componentCategory,
                String.valueOf(pk), 0, Instant.EPOCH);
    }
}
