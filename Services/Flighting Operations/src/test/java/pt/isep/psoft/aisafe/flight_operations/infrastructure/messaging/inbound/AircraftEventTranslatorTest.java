package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.reference.AircraftChange;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AircraftEventTranslatorTest {

    private static final Instant NOW = Instant.parse("2027-01-01T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AircraftEventTranslator translator = new AircraftEventTranslator(mapper);

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void agreedEnvelopeIsTranslated() throws Exception {
        JsonNode e = json("""
                {"eventId":"e-1","type":"AircraftStatusChanged","occurredAt":"2027-01-01T11:00:00Z",
                 "aggregateId":"CS-TTA","revision":4,
                 "payload":{"registrationNumber":"CS-TTA","modelCode":"A320","status":"UNDER_MAINTENANCE",
                            "maximumRange":6100.5,"seatingCapacity":180,"extra":"ignored"}}""");

        AircraftChange c = translator.translate(e, NOW);

        assertEquals("CS-TTA", c.registrationNumber());
        assertEquals("A320", c.modelCode());
        assertEquals("UNDER_MAINTENANCE", c.status());
        assertEquals(6100.5, c.maximumRange());
        assertEquals(180, c.seatingCapacity());
        assertEquals(4, c.revision());
        assertEquals(Instant.parse("2027-01-01T11:00:00Z"), c.changedAt());
        assertEquals("e-1", translator.eventId(e, "ignored"));
        assertFalse(translator.isGroundingRequest(e));
    }

    @Test
    void payloadAsTextAndRegistrationOnlyInAggregateId() throws Exception {
        JsonNode e = json("""
                {"eventId":"e-2","aggregateId":"CS-TTB","payload":"{\\"status\\":\\"ACTIVE\\"}"}""");

        AircraftChange c = translator.translate(e, NOW);

        assertEquals("CS-TTB", c.registrationNumber());
        assertEquals("ACTIVE", c.status());
        assertNull(c.maximumRange());
        assertEquals(0, c.revision());
        assertEquals(NOW, c.changedAt(), "without occurredAt the time of reception is used");
    }

    @Test
    void groundingRequestedIsRecognised() throws Exception {
        JsonNode e = json("""
                {"eventId":"e-3","type":"GroundingRequested","payload":{"registrationNumber":"CS-TTM"}}""");

        assertTrue(translator.isGroundingRequest(e));
        assertEquals("CS-TTM", translator.translate(e, NOW).registrationNumber());
    }

    @Test
    void eventIdFallsBackToMessageId() throws Exception {
        JsonNode e = json("""
                {"aggregateId":"CS-TTA","payload":{"status":"ACTIVE"}}""");

        assertEquals("msg-9", translator.eventId(e, "msg-9"));
        assertThrows(IllegalArgumentException.class, () -> translator.eventId(e, null));
    }

    @Test
    void malformedEventsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> translator.translate(json("{\"eventId\":\"x\",\"payload\":{\"status\":\"ACTIVE\"}}"), NOW),
                "no registration anywhere");
        assertThrows(IllegalArgumentException.class,
                () -> translator.translate(json("{\"aggregateId\":\"CS-TTA\",\"occurredAt\":\"ontem\"}"), NOW),
                "invalid date");
        assertThrows(IllegalArgumentException.class,
                () -> translator.translate(json("{\"aggregateId\":\"CS-TTA\",\"payload\":\"{not json\"}"), NOW),
                "invalid payload text");
    }
}
