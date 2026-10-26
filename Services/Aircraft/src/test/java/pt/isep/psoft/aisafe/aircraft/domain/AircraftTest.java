package pt.isep.psoft.aisafe.aircraft.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class AircraftTest {
    private Aircraft aircraft() {
        AircraftModel model = new AircraftModel(new ModelName("A320"), Manufacturer.AIRBUS,
                180, 24200.0, 6100.0, 840.0, 2.5, null, null, null, "Turbofan");
        return new Aircraft(new RegistrationNumber("CS-TTA"), model, LocalDate.of(2020, 1, 1), 180, "WiFi");
    }

    @Test
    void aircraftStartsActiveAndTracksUsage() {
        Aircraft aircraft = aircraft();
        assertEquals(AircraftStatus.ACTIVE, aircraft.getStatus());
        assertEquals(0.0, aircraft.getTotalFlightHours());
        assertEquals(0L, aircraft.getRevision());

        aircraft.incrementAssignments();
        aircraft.addFlightHours(3.5);
        assertEquals(1, aircraft.getTotalAssignments());
        assertEquals(3.5, aircraft.getTotalFlightHours());
        assertEquals(1L, aircraft.getRevision());
    }

    @Test
    void statusAndUsageRejectInvalidValues() {
        Aircraft aircraft = aircraft();
        assertThrows(IllegalArgumentException.class, () -> aircraft.updateStatus(null));
        assertThrows(IllegalArgumentException.class, () -> aircraft.addFlightHours(0.0));
        assertThrows(IllegalArgumentException.class, () -> aircraft.addFlightHours(-1.0));
    }
}
