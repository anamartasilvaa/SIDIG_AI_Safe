package pt.isep.psoft.aisafe.maintenance.application.DTO;

import jakarta.validation.constraints.NotBlank;
import pt.isep.psoft.aisafe.maintenance.domain.ComponentCategory;

public record RegisterMaintenanceDTO(
        @NotBlank String registrationNumber,
        String modelName,
        String description,
        Integer expectedDuration,
        ComponentCategory componentCategory,
        @NotBlank String startDate,
        Double cost,
        Long templateId,
        String templateGlobalId) {

    public RegisterMaintenanceDTO(String registrationNumber, String modelName, String description,
                                  Integer expectedDuration, ComponentCategory componentCategory,
                                  String startDate, Double cost, Long templateId) {
        this(registrationNumber, modelName, description, expectedDuration, componentCategory,
                startDate, cost, templateId, null);
    }
}
