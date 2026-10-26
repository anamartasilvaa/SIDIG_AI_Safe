package pt.isep.psoft.aisafe.maintenance.application.DTO;

import pt.isep.psoft.aisafe.maintenance.domain.ComponentCategory;

public record RegisterMaintenanceDTO(
        String registrationNumber,
        String modelName,
        String description,
        Integer expectedDuration,
        ComponentCategory componentCategory,
        String startDate,
        Double cost,
        Long templateId) {
}
