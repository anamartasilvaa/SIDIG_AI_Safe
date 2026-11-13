package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.reference.RouteChange;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AirportsRoutesTranslatorTest {

    private static final Instant NOW = Instant.parse("2027-01-01T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AirportsRoutesTranslator translator = new AirportsRoutesTranslator(mapper);

    private JsonNode envelope(String type, String method, String path, String payload, String lastModified) {
        var node = mapper.createObjectNode();
        node.put("eventType", type);
        node.put("method", method);
        node.put("path", path);
        node.put("payload", payload);
        node.put("lastModified", lastModified);
        node.put("originNodeId", "airports-routes-1");
        return node;
    }

    @Test
    void routeCreatedCarriesTheWholeRoute() {
        String body = "{\"routeId\":\"rt-opolis\",\"originIata\":\"opo\",\"destinationIata\":\"LIS\","
                + "\"estimatedFlightTime\":60,\"minimumRange\":300.0,\"minimumCapacity\":100}";
        RouteChange c = translator.translate(envelope("ROUTE_CREATED", "POST", "/api/routes", body,
                "2027-01-01T10:00:00Z"), NOW).orElseThrow();
        assertEquals("RT-OPOLIS", c.routeId());
        assertEquals("OPO", c.origin());
        assertEquals("LIS", c.destination());
        assertTrue(c.active());
        assertEquals(300.0, c.minimumRange());
        assertEquals(100, c.minimumCapacity());
        assertEquals(Instant.parse("2027-01-01T10:00:00Z"), c.changedAt());
    }

    @Test
    void routeDeactivatedTakesTheIdFromThePath() {
        RouteChange c = translator.translate(envelope("ROUTE_DEACTIVATED", "PATCH",
                "/api/routes/RT-OPOLIS/deactivate", null, null), NOW).orElseThrow();
        assertEquals("RT-OPOLIS", c.routeId());
        assertFalse(c.active());
        assertEquals(NOW, c.changedAt(), "without lastModified, the reception time is used");
    }

    @Test
    void routeUpdatedOnlyChangesTheSentFields() {
        RouteChange c = translator.translate(envelope("ROUTE_UPDATED", "PATCH", "/api/routes/RT-OPOLIS",
                "{\"minimumCapacity\":150}", "2027-01-01T11:00:00Z"), NOW).orElseThrow();
        assertEquals(150, c.minimumCapacity());
        assertNull(c.minimumRange());
        assertNull(c.active());
    }

    @Test
    void airportEventsAreIgnored() {
        assertTrue(translator.translate(envelope("AIRPORT_CREATED", "POST", "/api/airports", "{}", null), NOW).isEmpty());
    }

    @Test
    void malformedRouteEventsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                translator.translate(envelope("ROUTE_CREATED", "POST", "/api/routes", "not json", null), NOW));
        assertThrows(IllegalArgumentException.class, () ->
                translator.translate(envelope("ROUTE_DEACTIVATED", "PATCH", "/api/routes", null, null), NOW));
    }
}