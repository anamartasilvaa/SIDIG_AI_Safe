package pt.isep.psoft.aisafe.flight_operations.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueObjectsTest {

    @Test
    @DisplayName("RouteId e normalizado (trim + maiusculas)")
    void routeIdIsNormalized() {
        assertEquals("OPO-LIS", RouteId.of("  opo-lis ").value());
        assertEquals(RouteId.of("opo-lis"), RouteId.of("OPO-LIS"));
    }

    @Test
    @DisplayName("RegistrationNumber e normalizado e comparado por valor")
    void registrationIsNormalized() {
        assertEquals(RegistrationNumber.of("cs-tta"), RegistrationNumber.of(" CS-TTA "));
        assertEquals("CS-TTA", RegistrationNumber.of("cs-tta").value());
    }

    @Test
    @DisplayName("Valores vazios sao rejeitados")
    void blankValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RouteId.of(" "));
        assertThrows(IllegalArgumentException.class, () -> RegistrationNumber.of(null));
        assertThrows(IllegalArgumentException.class, () -> FlightId.of(""));
    }

    @Test
    @DisplayName("FlightId aceita apenas UUIDs e gera ids unicos")
    void flightIdIsUuid() {
        assertThrows(IllegalArgumentException.class, () -> FlightId.of("123"));
        FlightId id = FlightId.newId();
        assertEquals(id, FlightId.of(id.value()));
        assertNotEquals(FlightId.newId(), FlightId.newId());
    }
}
