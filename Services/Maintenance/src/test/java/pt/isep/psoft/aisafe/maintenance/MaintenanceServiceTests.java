package pt.isep.psoft.aisafe.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import pt.isep.psoft.aisafe.maintenance.application.MaintenanceService;
import pt.isep.psoft.aisafe.maintenance.application.DTO.CreateMaintenanceTemplateDTO;
import pt.isep.psoft.aisafe.maintenance.application.DTO.RegisterMaintenanceDTO;
import pt.isep.psoft.aisafe.maintenance.domain.ComponentCategory;
import pt.isep.psoft.aisafe.maintenance.repositories.MaintenanceRecordRepository;
import pt.isep.psoft.aisafe.maintenance.repositories.MaintenanceTemplateRepository;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MaintenanceServiceTests {
    @MockitoBean
    private pt.isep.psoft.aisafe.maintenance.infrastructure.AircraftClient aircraftClient;

    @MockitoBean
    private pt.isep.psoft.aisafe.maintenance.infrastructure.MaintenancePeerClient maintenancePeerClient;

    @Autowired
    MaintenanceTemplateRepository templates;
    @Autowired
    MaintenanceRecordRepository records;
    @Autowired
    MaintenanceService service;

    @Test
    void createTemplatePersistsAndFindsByName() {
        doNothing().when(aircraftClient).updateMaintenanceStatus(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        CreateMaintenanceTemplateDTO dto = new CreateMaintenanceTemplateDTO(
                "A320 CHECK",
                "LINE",
                100.0,
                30,
                "A320 Checklist",
                "1.0",
                java.util.List.of(new pt.isep.psoft.aisafe.maintenance.application.DTO.ChecklistItemDTO("Inspect landing gear", true)),
                java.util.List.of("A320")
        );
        var created = service.createTemplate(dto);
        assertEquals("A320 CHECK", created.templateName());
        assertTrue(templates.findByTemplateName("A320 CHECK").isPresent());
    }

    @Test
    void registerMaintenanceCreatesRecord() {
        when(aircraftClient.getAircraftModelName("CS-TOM")).thenReturn("B737");
        doNothing().when(aircraftClient).updateMaintenanceStatus("CS-TOM", "UNDER_MAINTENANCE");
        CreateMaintenanceTemplateDTO templateDto = new CreateMaintenanceTemplateDTO(
                "B737 CHECK",
                "LINE",
                200.0,
                45,
                "B737 Checklist",
                "1.0",
                java.util.List.of(new pt.isep.psoft.aisafe.maintenance.application.DTO.ChecklistItemDTO("Check engine", true)),
                java.util.List.of("B737")
        );
        var template = service.createTemplate(templateDto);
        var dto = new RegisterMaintenanceDTO("CS-TOM", "B737", "Routine inspection", 8, ComponentCategory.ENGINE,
                java.time.LocalDateTime.now().toString(), 1500.0, template.pk());
        var record = service.registerMaintenance(dto);
        assertEquals("CS-TOM", record.registrationNumber());
        assertEquals(1, records.findByRegistrationNumberOrderByStartDateDesc("CS-TOM").size());
    }
}
