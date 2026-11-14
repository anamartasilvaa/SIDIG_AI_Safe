package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.IntegrationEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlightEventParsingTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final FlightState state = new FlightState("11111111-1111-1111-1111-111111111111", "RT-OPOLIS",
            "CS-TTA", LocalDateTime.parse("2027-03-15T10:00:00"), null, null, "DELAYED", null, 2,
            Instant.parse("2027-03-01T11:00:00Z"), Instant.parse("2027-03-01T10:00:00Z"), "fo-1");

    private IntegrationEvent event(String type, Object payload) throws Exception {
        IntegrationEvent e = new IntegrationEvent("e-1", type, 1, Instant.now(), state.flightId(), 2, "fo-1",
                "c-1", mapper.valueToTree(payload));
        // round-trip through JSON, exactly as on the wire
        return mapper.readValue(mapper.writeValueAsString(e), IntegrationEvent.class);
    }

    @Test
    void scheduledCarriesTheStateDirectly() throws Exception {
        assertEquals(state, FlightEventListener.toFlightState(event("FlightScheduled", state), mapper));
    }

    @Test
    void statusChangedCarriesTheStateInsideFlight() throws Exception {
        Object payload = Map.of("previousStatus", "SCHEDULED", "flight", state);
        assertEquals(state, FlightEventListener.toFlightState(event("FlightStatusChanged", payload), mapper));
    }

    @Test
    void incompleteStateIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                FlightEventListener.toFlightState(event("FlightScheduled", Map.of("flightId", "x")), mapper));
    }
}
