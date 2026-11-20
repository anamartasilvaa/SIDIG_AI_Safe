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
import pt.isep.psoft.aisafe.airports_routes_service.domain.ProcessedEvent;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ProcessedEventRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Consumidor AMQP: ignora eventos proprios, aplica os alheios e rejeita (sem reenfileirar) os invalidos. */
@ExtendWith(MockitoExtension.class)
class ReplicationEventConsumerTest {

    private static final String SELF = "airports-routes-1";
    private static final String OTHER = "airports-routes-2";

    @Mock AirportService airportService;
    @Mock RouteService routeService;
    @Mock ProcessedEventRepository processedEvents;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ReplicationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        IdempotencyGuard guard = new IdempotencyGuard(processedEvents, Duration.ofDays(7));
        consumer = new ReplicationEventConsumer(airportService, routeService, mapper, guard, SELF);
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

    // ---------- Inbox (processed_event) ----------

    @Test
    void appliedEventIsRecordedInProcessedEvent() {
        Instant when = Instant.parse("2026-11-15T10:00:00Z");

        consumer.onEvent(message("ROUTE_DEACTIVATED", "/api/routes/RT-1/deactivate", null, OTHER, when));

        org.mockito.ArgumentCaptor<ProcessedEvent> captor = org.mockito.ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(captor.capture());
        assertEquals("airports-replication", captor.getValue().getConsumer());
        assertEquals(OTHER + "-1", captor.getValue().getEventId());   // originNodeId-taskId
        assertEquals("ROUTE_DEACTIVATED", captor.getValue().getEventType());
    }

    @Test
    void duplicateMessageIsIgnored() {
        Instant when = Instant.parse("2026-11-15T10:00:00Z");
        when(processedEvents.existsByConsumerAndEventId("airports-replication", OTHER + "-1"))
                .thenReturn(false, true);   // 1.a entrega: novo; 2.a entrega (redelivery): ja processado

        consumer.onEvent(message("ROUTE_DEACTIVATED", "/api/routes/RT-1/deactivate", null, OTHER, when));
        consumer.onEvent(message("ROUTE_DEACTIVATED", "/api/routes/RT-1/deactivate", null, OTHER, when));

        verify(routeService, times(1)).deactivateRoute("RT-1", true, when, OTHER);
        verify(processedEvents, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    void amqpMessageIdIsUsedAsTheEventId() {
        Instant when = Instant.parse("2026-11-15T10:00:00Z");
        Message withId = message("ROUTE_DEACTIVATED", "/api/routes/RT-1/deactivate", null, OTHER, when);
        withId.getMessageProperties().setMessageId("airports-routes-2-42");

        consumer.onEvent(withId);

        verify(processedEvents).existsByConsumerAndEventId(eq("airports-replication"), eq("airports-routes-2-42"));
    }

    @Test
    void envelopeEventIdTakesPrecedence() throws Exception {
        Instant when = Instant.parse("2026-11-15T10:00:00Z");
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", "0f8b1c2e-0000-4000-8000-000000000001");
        envelope.put("taskId", "1");
        envelope.put("eventType", "ROUTE_DEACTIVATED");
        envelope.put("path", "/api/routes/RT-1/deactivate");
        envelope.put("lastModified", when.toString());
        envelope.put("originNodeId", OTHER);
        Message m = MessageBuilder.withBody(envelope.toString().getBytes(StandardCharsets.UTF_8))
                .setMessageId("something-else").build();

        consumer.onEvent(m);

        verify(processedEvents).existsByConsumerAndEventId("airports-replication", "0f8b1c2e-0000-4000-8000-000000000001");
    }

    @Test
    void failedEventIsNotRecordedSoItCanBeRetried() {
        Instant when = Instant.parse("2026-11-15T10:00:00Z");
        doThrow(new IllegalStateException("boom")).when(routeService).deactivateRoute("RT-1", true, when, OTHER);

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.onEvent(
                message("ROUTE_DEACTIVATED", "/api/routes/RT-1/deactivate", null, OTHER, when)));

        verify(processedEvents, never()).save(any(ProcessedEvent.class));
    }

    @Test
    void ownEventIsNotRecorded() {
        consumer.onEvent(message("ROUTE_DEACTIVATED", "/api/routes/RT-1/deactivate", null, SELF, Instant.now()));

        verifyNoInteractions(processedEvents);
    }
}
