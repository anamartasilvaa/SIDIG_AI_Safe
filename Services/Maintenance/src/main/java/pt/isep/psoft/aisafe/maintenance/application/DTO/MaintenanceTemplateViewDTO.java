package pt.isep.psoft.aisafe.maintenance.application.DTO;

import java.util.List;
import java.time.Instant;

public record MaintenanceTemplateViewDTO(
        Long pk,
        String templateName,
        String templateType,
        Double flightHoursInterval,
        Integer calendarDaysInterval,
        String checklistTitle,
        String checklistVersion,
        List<ChecklistItemDTO> checklistItems,
        List<String> appliesToModelNames,
        String globalId,
        long revision,
        Instant lastModified) {

    public MaintenanceTemplateViewDTO(Long pk, String templateName, String templateType,
                                      Double flightHoursInterval, Integer calendarDaysInterval,
                                      String checklistTitle, String checklistVersion,
                                      List<ChecklistItemDTO> checklistItems,
                                      List<String> appliesToModelNames) {
        this(pk, templateName, templateType, flightHoursInterval, calendarDaysInterval,
                checklistTitle, checklistVersion, checklistItems, appliesToModelNames,
                String.valueOf(pk), 0, Instant.EPOCH);
    }
}
