package pt.isep.psoft.aisafe.maintenance.application.DTO;

import java.util.List;

public record MaintenanceTemplateViewDTO(
        Long pk,
        String templateName,
        String templateType,
        Double flightHoursInterval,
        Integer calendarDaysInterval,
        String checklistTitle,
        String checklistVersion,
        List<ChecklistItemDTO> checklistItems,
        List<String> appliesToModelNames) {
}
