package pt.isep.psoft.aisafe.airports_routes_service.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ReplicationTaskRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Outbox -> broker: DONE so com confirmacao do broker, repeticao com backoff e FAILED as 10 tentativas. */
@ExtendWith(MockitoExtension.class)
class AmqpReplicationPublisherTest {

    @Mock ReplicationTaskRepository repository;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock RabbitOperations operations;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private AmqpReplicationPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AmqpReplicationPublisher(repository, rabbitTemplate, mapper, "airports.events");
    }

    private ReplicationTask task() {
        return new ReplicationTask("amqp://broker", "POST", "/api/airports", "{\"iataCode\":\"OPO\"}",
                "backoffice", Instant.parse("2026-11-15T10:00:00Z"), "airports-routes-1");
    }

    private void pending(ReplicationTask task) {
        when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
                eq(ReplicationTask.Status.PENDING), any(Instant.class))).thenReturn(List.of(task));
        when(rabbitTemplate.invoke(any())).thenAnswer(invocation ->
                ((RabbitOperations.OperationsCallback<?>) invocation.getArgument(0)).doInRabbit(operations));
    }

    @Test
    void taskIsDoneOnlyAfterTheBrokerConfirms() {
        ReplicationTask task = task();
        pending(task);

        publisher.publishPendingTasks();

        verify(operations).send(eq("airports.events"), eq(""), any(Message.class));
        verify(operations).waitForConfirmsOrDie(anyLong());
        assertEquals(ReplicationTask.Status.DONE, task.getStatus());
        verify(repository).save(task);
    }

    @Test
    void messageIsPersistentJsonWithTheEnvelope() throws Exception {
        ReplicationTask task = task();
        pending(task);

        publisher.publishPendingTasks();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(operations).send(anyString(), anyString(), captor.capture());
        Message message = captor.getValue();
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
        assertEquals("AIRPORT_CREATED", message.getMessageProperties().getType());
        assertEquals("airports-routes-1", message.getMessageProperties().getHeader("originNodeId"));

        JsonNode envelope = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertEquals("AIRPORT_CREATED", envelope.get("eventType").asText());
        assertEquals("/api/airports", envelope.get("path").asText());
        assertEquals("backoffice", envelope.get("originalUser").asText());
        assertEquals("airports-routes-1", envelope.get("originNodeId").asText());
        assertEquals("2026-11-15T10:00:00Z", envelope.get("lastModified").asText());
        assertEquals(task.getEventId(), envelope.get("eventId").asText());
        assertEquals(task.getEventId(), message.getMessageProperties().getMessageId());
    }

    @Test
    void everyTaskGetsADifferentUuidEventId() {
        ReplicationTask a = task();
        ReplicationTask b = task();

        assertNotNull(a.getEventId());
        assertEquals(36, a.getEventId().length());
        assertNotEquals(a.getEventId(), b.getEventId());
    }

    @Test
    void withoutBrokerConfirmationTheTaskStaysPendingWithBackoff() {
        ReplicationTask task = task();
        pending(task);
        doThrow(new IllegalStateException("nack")).when(operations).waitForConfirmsOrDie(anyLong());
        Instant before = Instant.now();

        publisher.publishPendingTasks();

        assertEquals(ReplicationTask.Status.PENDING, task.getStatus());
        assertEquals(1, task.getAttempts());
        assertTrue(task.getNextAttemptAt().isAfter(before.plusSeconds(4)), "backoff de 5 s na 1.a falha");
        verify(repository).save(task);
    }

    @Test
    void taskFailsDefinitivelyAfterTenAttempts() {
        ReplicationTask task = task();
        pending(task);
        doThrow(new IllegalStateException("broker down")).when(operations).waitForConfirmsOrDie(anyLong());

        for (int i = 0; i < 10; i++) {
            publisher.publishPendingTasks();
        }

        assertEquals(10, task.getAttempts());
        assertEquals(ReplicationTask.Status.FAILED, task.getStatus());
    }

    @Test
    void backoffGrowsAndIsCappedAtSixtySeconds() {
        ReplicationTask task = task();
        pending(task);
        doThrow(new IllegalStateException("broker down")).when(operations).waitForConfirmsOrDie(anyLong());

        for (int i = 0; i < 9; i++) {
            publisher.publishPendingTasks();
        }

        long wait = task.getNextAttemptAt().getEpochSecond() - Instant.now().getEpochSecond();
        assertTrue(wait <= 60 && wait >= 58, "teto de 60 s, mas foi " + wait);
    }

    @Test
    void nothingIsPublishedWhenThereAreNoPendingTasks() {
        when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
                eq(ReplicationTask.Status.PENDING), any(Instant.class))).thenReturn(List.of());

        publisher.publishPendingTasks();

        verifyNoInteractions(rabbitTemplate);
        verify(repository, never()).save(any());
    }
}
