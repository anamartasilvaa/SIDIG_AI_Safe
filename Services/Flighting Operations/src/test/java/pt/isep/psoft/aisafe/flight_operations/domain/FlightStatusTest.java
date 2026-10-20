package pt.isep.psoft.aisafe.flight_operations.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus.*;

class FlightStatusTest {

    @Test
    @DisplayName("Transicoes permitidas pelo ciclo de vida")
    void allowedTransitions() {
        assertTrue(SCHEDULED.canTransitionTo(DELAYED));
        assertTrue(SCHEDULED.canTransitionTo(IN_FLIGHT));
        assertTrue(SCHEDULED.canTransitionTo(CANCELED));
        assertTrue(DELAYED.canTransitionTo(IN_FLIGHT));
        assertTrue(DELAYED.canTransitionTo(CANCELED));
        assertTrue(IN_FLIGHT.canTransitionTo(COMPLETED));
    }

    @Test
    @DisplayName("Transicoes proibidas e estados finais")
    void forbiddenTransitions() {
        assertFalse(IN_FLIGHT.canTransitionTo(CANCELED));
        assertFalse(COMPLETED.canTransitionTo(SCHEDULED));
        assertFalse(SCHEDULED.canTransitionTo(COMPLETED));
        assertTrue(COMPLETED.allowedNext().isEmpty());
        assertTrue(CANCELED.allowedNext().isEmpty());
    }

    @Test
    @DisplayName("Parse aceita minusculas e rejeita valores desconhecidos")
    void parse() {
        assertEquals(CANCELED, FlightStatus.parse(" canceled "));
        assertThrows(IllegalArgumentException.class, () -> FlightStatus.parse("CANCELLED"));
        assertThrows(IllegalArgumentException.class, () -> FlightStatus.parse(""));
    }

    @Test
    @DisplayName("Um voo cancelado nao ocupa o aviao")
    void canceledDoesNotOccupyAircraft() {
        assertFalse(CANCELED.occupiesAircraft());
        assertTrue(SCHEDULED.occupiesAircraft());
    }
}
