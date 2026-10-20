package pt.isep.psoft.aisafe.flight_operations.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlightSchedulingPolicyTest {

    private final FlightSchedulingPolicy policy = new FlightSchedulingPolicy(Duration.ofHours(2));

    private static final RouteRequirements LIS = new RouteRequirements("OPO-LIS", true, 300.0, null, 50);
    private static final RouteRequirements JFK = new RouteRequirements("OPO-JFK", true, 5300.0, 5500.0, 150);
    private static final RouteRequirements INACTIVE = new RouteRequirements("OPO-FNC", false, 1000.0, null, 50);

    private static final AircraftCapabilities LONG_RANGE = new AircraftCapabilities("CS-TTA", "ACTIVE", 6000.0, 180);
    private static final AircraftCapabilities SHORT_RANGE = new AircraftCapabilities("CS-TTB", "ACTIVE", 2500.0, 120);
    private static final AircraftCapabilities IN_MAINTENANCE = new AircraftCapabilities("CS-TTM", "UNDER_MAINTENANCE", 6000.0, 180);
    private static final AircraftCapabilities SMALL = new AircraftCapabilities("CS-SML", "ACTIVE", 6000.0, 100);

    // ------------------------------------------------------------- atribuicao

    @Test
    @DisplayName("Aviao ativo com alcance e capacidade suficientes e aceite")
    void validAssignment() {
        assertDoesNotThrow(() -> policy.checkAssignment(LIS, LONG_RANGE));
        assertDoesNotThrow(() -> policy.checkAssignment(JFK, LONG_RANGE));
    }

    @Test
    @DisplayName("Rota inativa e rejeitada")
    void inactiveRoute() {
        assertThrows(SchedulingRuleViolation.class, () -> policy.checkAssignment(INACTIVE, LONG_RANGE));
    }

    @Test
    @DisplayName("Aviao em manutencao e rejeitado")
    void aircraftInMaintenance() {
        assertThrows(SchedulingRuleViolation.class, () -> policy.checkAssignment(LIS, IN_MAINTENANCE));
    }

    @Test
    @DisplayName("Alcance insuficiente e rejeitado (usa minimumRange da rota)")
    void insufficientRange() {
        assertThrows(SchedulingRuleViolation.class, () -> policy.checkAssignment(JFK, SHORT_RANGE));
    }

    @Test
    @DisplayName("Capacidade abaixo do minimo da rota e rejeitada")
    void insufficientCapacity() {
        assertThrows(SchedulingRuleViolation.class, () -> policy.checkAssignment(JFK, SMALL));
    }

    @Test
    @DisplayName("Dados em falta nos outros servicos nao bloqueiam o agendamento")
    void missingDataIsNotBlocking() {
        assertDoesNotThrow(() -> policy.checkAssignment(
                new RouteRequirements("X", true, null, null, null),
                new AircraftCapabilities("Y", null, null, null)));
    }

    // -------------------------------------------------------------- conflitos

    private static final LocalDateTime T = LocalDateTime.of(2027, 3, 15, 10, 0);
    private static final List<FlightSlot> EXISTING = List.of(
            new FlightSlot("a", T, FlightStatus.SCHEDULED),
            new FlightSlot("b", T.plusHours(5), FlightStatus.CANCELED));

    @Test
    @DisplayName("Voo a menos de 2 h (antes ou depois) e conflito")
    void conflictWithinGap() {
        Optional<FlightSlot> conflict = policy.findConflict(EXISTING, T.plusHours(1));
        assertTrue(conflict.isPresent());
        assertEquals("a", conflict.get().flightId());
        assertTrue(policy.findConflict(EXISTING, T.minusMinutes(30)).isPresent());
    }

    @Test
    @DisplayName("Exatamente 2 h de intervalo nao e conflito")
    void exactGapIsAllowed() {
        assertTrue(policy.findConflict(EXISTING, T.plusHours(2)).isEmpty());
    }

    @Test
    @DisplayName("Voos cancelados nao contam como conflito")
    void canceledFlightsAreIgnored() {
        assertTrue(policy.findConflict(EXISTING, T.plusHours(5)).isEmpty());
    }

    @Test
    @DisplayName("Intervalo minimo negativo e invalido")
    void negativeGapIsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> new FlightSchedulingPolicy(Duration.ofHours(-1)));
    }
}
