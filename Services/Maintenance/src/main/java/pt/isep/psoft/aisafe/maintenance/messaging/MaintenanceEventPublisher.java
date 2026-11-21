package pt.isep.psoft.aisafe.maintenance.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pt.isep.psoft.aisafe.maintenance.domain.MaintenanceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

@Component
public class MaintenanceEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceEventPublisher.class);
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final String exchange;

    public MaintenanceEventPublisher(RabbitTemplate rabbit, ObjectMapper mapper,
                                     @Value("${app.messaging.maintenance-exchange:maintenance.events}") String exchange) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.exchange = exchange;
    }

    public void maintenanceStarted(MaintenanceRecord record) {
        publish("MaintenanceStarted", "maintenance.started", record);
    }

    public void maintenanceCompleted(MaintenanceRecord record) {
        publish("MaintenanceCompleted", "maintenance.completed", record);
    }

    private void publish(String type, String routingKey, MaintenanceRecord record) {
        String eventId = UUID.randomUUID().toString();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", eventId);
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("aggregateId", record.getGlobalId());
        envelope.put("revision", record.getRevision());
        ObjectNode payload = envelope.putObject("payload");
        payload.put("registrationNumber", record.getRegistrationNumber());
        payload.put("modelName", record.getModelName());
        payload.put("status", type.endsWith("Started") ? "UNDER_MAINTENANCE" : "ACTIVE");

        String body = envelope.toString();
        Runnable send = () -> send(type, routingKey, eventId, body);
        // Sent only after the transaction commits: a rolled-back maintenance is never announced.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void send(String type, String routingKey, String eventId, String body) {
        try {
            rabbit.convertAndSend(exchange, routingKey, body, message -> {
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId(eventId);
                    message.getMessageProperties().setType(type);
                    return message;
                });
        } catch (RuntimeException ex) {
            // No outbox in this service: with the broker unreachable the event is lost (known limitation).
            log.warn("Maintenance event {} ({}) was not published: {}", eventId, routingKey, ex.getMessage());
        }
    }
}
