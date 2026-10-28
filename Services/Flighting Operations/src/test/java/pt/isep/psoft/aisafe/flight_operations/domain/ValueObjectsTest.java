package pt.isep.psoft.aisafe.flight_operations.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueObjectsTest {

    @Test
    @DisplayName("RouteId is normalized (trim + uppercase)")
    void routeIdIsNormalized() {
        assertEquals("OPO-LIS", RouteId.of("  opo-lis ").value());
        assertEquals(RouteId.of("opo-lis"), RouteId.of("OPO-LIS"));
    }

    @Test
    @DisplayName("RegistrationNumber is normalized and compared by value")
    void registrationIsNormalized() {
        assertEquals(RegistrationNumber.of("cs-tta"), RegistrationNumber.of(" CS-TTA "));
        assertEquals("CS-TTA", RegistrationNumber.of("cs-tta").value());
    }

    @Test
    @DisplayName("Blank values are rejected")
    void blankValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RouteId.of(" "));
        assertThrows(IllegalArgumentException.class, () -> RegistrationNumber.of(null));
        assertThrows(IllegalArgumentException.class, () -> FlightId.of(""));
    }

    @Test
    @DisplayName("FlightId only accepts UUIDs and generates unique ids")
    void flightIdIsUuid() {
        assertThrows(IllegalArgumentException.class, () -> FlightId.of("123"));
        FlightId id = FlightId.newId();
        assertEquals(id, FlightId.of(id.value()));
        assertNotEquals(FlightId.newId(), FlightId.newId());
    }
}