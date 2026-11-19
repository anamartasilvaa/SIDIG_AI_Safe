package pt.isep.psoft.aisafe.airports_routes_service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Consumidor AMQP: ignora eventos proprios, aplica os alheios e rejeita (sem reenfileirar) os invalidos. */
@ExtendWith(MockitoExtension.class)
class ReplicationEventConsumerTest {

    private static final String SELF = "airports-routes-1";
    private static final String OTHER = "airports-routes-2";

    @Mock AirportService airportService;
    @Mock RouteService routeService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ReplicationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ReplicationEventConsumer(airportService, routeService, mapper, SELF);
    }

    private Message message(String eventType, String path, String payload, String origin, Instant lastModified) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("taskId", "1");
        envelope.put("eventType", eventType);
        envelope.put("path", path);
        envelope.put("payload", payload);
        envelope.put("lastModified", lastModified == null ? null : lastModified.toString());
        envelope.put("originNodeId", origin);
        return MessageBuilder.withBody(envelope.toString().getBytes(StandardCharsets.UTF_8)).build();
    }

    @Test
    void ownEventReturnedByTheFanoutIsIgnored() {
        consumer.onEvent(message("ROUTE_DEACTIVATED", "/api/routes/RT-1/deactivate", null, SELF, Instant.now()));

        verifyNoInteractions(airportService, routeService);
    }

    @Test
    void eventFromAnotherInstanceIsApplied() {
        Instant when = Instant.parse("2026-11-15T10:00:00Z");

        consumer.onEvent(message("ROUTE_DEACTIVATED", "/api/routes/RT-1/deactivate", null, OTHER, when));

        verify(routeService).deactivateRoute("RT-1", true, when, OTHER);
    }

    @Test
    void airportStatusEventIsAppliedAsReplication() throws Exception {
        Instant when = Instant.parse("2026-11-15T10:00:00Z");

        consumer.onEvent(message("AIRPORT_STATUS_CHANGED", "/api/airports/OPO/status",
                mapper.writeValueAsString("CLOSED"), OTHER, when));

        verify(airportService).updateAirportStatus("OPO", "CLOSED", true, when, OTHER);
    }

    @Test
    void imageEventIsAppliedWithTheDataUrl() throws Exception {
        Instant when = Instant.parse("2026-11-15T10:00:00Z");
        String dataUrl = "data:image/png;base64,AAAA";

        consumer.onEvent(message("AIRPORT_IMAGE_UPDATED", "/api/airports/OPO/image",
                mapper.writeValueAsString(dataUrl), OTHER, when));

        verify(airportService).applyReplicatedImage("OPO", dataUrl, when, OTHER);
    }

    @Test
    void unknownEventTypeIsRejectedWithoutRequeue() {
        Message bad = message("NOT_AN_EVENT", "/api/airports/OPO", null, OTHER, null);

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.onEvent(bad));
        verifyNoInteractions(airportService, routeService);
    }

    @Test
    void malformedBodyIsRejectedWithoutRequeue() {
        Message bad = MessageBuilder.withBody("not json".getBytes(StandardCharsets.UTF_8)).build();

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.onEvent(bad));
    }

    @Test
    void failureInTheServiceIsRejectedWithoutRequeue() {
        Instant when = Instant.parse("2026-11-15T10:00:00Z");
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(routeService).deactivateRoute("RT-1", true, when, OTHER);

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.onEvent(
                message("ROUTE_DEACTIVATED", "/api/routes/RT-1/deactivate", null, OTHER, when)));
    }
}
