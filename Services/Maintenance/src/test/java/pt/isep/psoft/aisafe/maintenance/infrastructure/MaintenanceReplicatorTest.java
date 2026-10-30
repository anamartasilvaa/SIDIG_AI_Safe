package pt.isep.psoft.aisafe.maintenance.infrastructure;

import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.maintenance.application.DTO.MaintenanceRecordViewDTO;
import pt.isep.psoft.aisafe.maintenance.application.DTO.MaintenanceTemplateViewDTO;
import pt.isep.psoft.aisafe.maintenance.domain.ComponentCategory;
import pt.isep.psoft.aisafe.maintenance.domain.MaintenanceRecordStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MaintenanceReplicatorTest {

    @Test
    void queuedRecordIsSentToEveryConfiguredPeer() throws Exception {
        RemoteCaller caller = mock(RemoteCaller.class);
        when(caller.put(any(), any(), eq(Void.class))).thenReturn(Optional.empty());
        MaintenanceReplicator replicator = new MaintenanceReplicator(
                caller, List.of("http://maintenance-2", "http://maintenance-3"),
                true, java.time.Duration.ofMillis(10));

        try {
            MaintenanceRecordViewDTO record = new MaintenanceRecordViewDTO(
                    1L, "CS-TOM", "B737", "B737 CHECK", "Routine inspection",
                    LocalDateTime.now(), 8, null, 1500.0, null,
                    MaintenanceRecordStatus.SCHEDULED, ComponentCategory.ENGINE,
                    "record-1", 0, Instant.now());
            replicator.replicate(record);

            verify(caller, timeout(1000)).put(
                    eq("http://maintenance-2/internal/maintenance/records/record-1/replica"),
                    eq(record), eq(Void.class));
            verify(caller, timeout(1000)).put(
                    eq("http://maintenance-3/internal/maintenance/records/record-1/replica"),
                    eq(record), eq(Void.class));
        } finally {
            replicator.shutdown();
        }
    }

    @Test
    void queuedTemplateIsSentToEveryConfiguredPeer() {
            RemoteCaller caller = mock(RemoteCaller.class);
            when(caller.put(any(), any(), eq(Void.class))).thenReturn(Optional.empty());
            MaintenanceReplicator replicator = new MaintenanceReplicator(
                    caller, List.of("http://maintenance-2", "http://maintenance-3"),
                    true, java.time.Duration.ofMillis(10));
            try {
                MaintenanceTemplateViewDTO template = new MaintenanceTemplateViewDTO(
                        1L, "B737 CHECK", "LINE", 100.0, 30,
                        "B737 checklist", "1", List.of(), List.of("B737"),
                        "template-1", 0, Instant.now());
                replicator.replicateTemplate(template);

                verify(caller, timeout(1000)).put(
                        eq("http://maintenance-2/internal/maintenance/templates/template-1/replica"),
                        eq(template), eq(Void.class));
                verify(caller, timeout(1000)).put(
                        eq("http://maintenance-3/internal/maintenance/templates/template-1/replica"),
                        eq(template), eq(Void.class));
            } finally {
                replicator.shutdown();
            }
    }
}
