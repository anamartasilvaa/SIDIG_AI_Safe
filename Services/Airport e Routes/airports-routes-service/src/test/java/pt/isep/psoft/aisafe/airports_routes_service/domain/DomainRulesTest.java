package pt.isep.psoft.aisafe.airports_routes_service.domain;

import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.airports_routes_service.application.Coordinates;

import static org.junit.jupiter.api.Assertions.*;

class DomainRulesTest {

    @Test
    void testIataCodeValidationSuccess() {
        IATACode code = new IATACode("OPO");
        assertEquals("OPO", code.code());
    }

    @Test
    void testIataCodeValidationFailure() {
        assertThrows(IllegalArgumentException.class, () -> new IATACode("op")); // Menos de 3 letras / minúsculas
        assertThrows(IllegalArgumentException.class, () -> new IATACode("OPO1")); // Contém números
    }

    @Test
    void testCoordinatesValidation() {
        Coordinates valid = new Coordinates(41.2356, -8.6781);
        assertEquals(41.2356, valid.latitude());

        // Latitude fora dos limites (-90 a 90)
        assertThrows(IllegalArgumentException.class, () -> new Coordinates(95.0, 0.0));
    }

    @Test
    void testRouteIdValidation() {
        RouteId routeId = new RouteId("RT-OPOLIS");
        assertEquals("RT-OPOLIS", routeId.id());

        assertThrows(IllegalArgumentException.class, () -> new RouteId("   "));
    }
}