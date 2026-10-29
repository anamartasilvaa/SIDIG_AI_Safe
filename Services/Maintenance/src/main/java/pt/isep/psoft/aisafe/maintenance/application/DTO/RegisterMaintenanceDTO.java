package pt.isep.psoft.aisafe.maintenance.application.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import pt.isep.psoft.aisafe.maintenance.domain.ComponentCategory;

public record RegisterMaintenanceDTO(
        @NotBlank String registrationNumber,
        String modelName,
        String description,
        Integer expectedDuration,
        ComponentCategory componentCategory,
        @NotBlank String startDate,
        Double cost,
        @NotNull Long templateId) {
}
