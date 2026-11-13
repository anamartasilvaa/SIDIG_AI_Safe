package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.EventTypes;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.IntegrationEvent;

import java.time.Clock;
import java.util.UUID;

@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String instanceId;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper eventMapper, Clock clock,
                        @Value("${app.instance-id}") String instanceId) {
        this.repository = repository;
        this.mapper = eventMapper;
        this.clock = clock;
        this.instanceId = instanceId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IntegrationEvent append(String exchange, String routingKey, String type,
                                   String aggregateId, long revision, Object payload, String correlationId) {
        IntegrationEvent event = new IntegrationEvent(
                UUID.randomUUID().toString(), type, EventTypes.CURRENT_VERSION, clock.instant(),
                aggregateId, revision, instanceId, correlationId, mapper.valueToTree(payload));
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event " + type + " for " + aggregateId, e);
        }
        repository.save(new OutboxEvent(event.eventId(), exchange, routingKey, type,
                aggregateId, correlationId, json, event.occurredAt()));
        return event;
    }
}