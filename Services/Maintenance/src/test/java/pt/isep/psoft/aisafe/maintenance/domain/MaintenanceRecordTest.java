package pt.isep.psoft.aisafe.maintenance.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceRecordTest {
    private MaintenanceRecord record() {
        return new MaintenanceRecord("CS-TTA", "A320", "Routine inspection", 8,
                ComponentCategory.ENGINE, LocalDateTime.of(2026, 10, 25, 10, 0),
                1500.0, "A320 CHECK");
    }

    @Test
    void recordStartsScheduledAndCompletionAdvancesRevision() {
        MaintenanceRecord record = record();
        assertEquals(MaintenanceRecordStatus.SCHEDULED, record.getStatus());
        assertEquals(0L, record.getRevision());

        record.complete("Inspection completed");

        assertEquals(MaintenanceRecordStatus.COMPLETED, record.getStatus());
        assertEquals("Inspection completed", record.getCompletionNotes());
        assertNotNull(record.getCompletionDate());
        assertEquals(1L, record.getRevision());
    }

    @Test
    void invalidRecordAndCompletionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MaintenanceRecord(
                "CS-TTA", "A320", "Inspection", 8, ComponentCategory.ENGINE,
                LocalDateTime.now(), -1.0, "CHECK"));
        assertThrows(IllegalArgumentException.class, () -> record().complete(" "));
    }
}
