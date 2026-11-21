package pt.isep.psoft.aisafe.aircraft.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pt.isep.psoft.aisafe.aircraft.domain.Aircraft;
import pt.isep.psoft.aisafe.aircraft.domain.AircraftModel;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes the Aircraft events on the topic exchange aircraft.events, with the contract consumed by
 * Flight Operations and Maintenance:
 * {eventId, type, version, occurredAt, aggregateId, revision,
 *  payload: {registrationNumber, modelCode, modelName, status, maximumRange, seatingCapacity}}.
 * Routing keys:
 * - aircraft.registered: new aircraft;
 * - aircraft.status-changed: status changed by an event (e.g. maintenance); Flight Operations does the
 *   grounding from the shared queue fo.grounding;
 * - aircraft.updated: any other change, including a status changed through the REST API, whose grounding
 *   is already requested synchronously by this service.
 * The message is sent only after the current transaction commits, so a rolled-back change is never published.
 */
@Slf4j
@Component
public class AircraftEventPublisher {
    static final String REGISTERED = "aircraft.registered";
    static final String STATUS_CHANGED = "aircraft.status-changed";
    static final String UPDATED = "aircraft.updated";

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final String exchange;

    public AircraftEventPublisher(RabbitTemplate rabbit, ObjectMapper mapper,
                                  @Value("${app.messaging.aircraft-exchange:aircraft.events}") String exchange) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.exchange = exchange;
    }

    public void aircraftRegistered(Aircraft aircraft) {
        publish("AircraftRegistered", REGISTERED, aircraft);
    }

    public void aircraftStatusChanged(Aircraft aircraft) {
        publish("AircraftStatusChanged", STATUS_CHANGED, aircraft);
    }

    public void aircraftUpdated(Aircraft aircraft) {
        publish("AircraftUpdated", UPDATED, aircraft);
    }

    /** Kept for compatibility: any change without a more specific meaning. */
    public void aircraftChanged(Aircraft aircraft) {
        aircraftUpdated(aircraft);
    }

    String toJson(String type, String eventId, Aircraft aircraft) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", eventId);
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("aggregateId", aircraft.getRegistrationNumber().number());
        envelope.put("revision", aircraft.getRevision());
        ObjectNode payload = envelope.putObject("payload");
        payload.put("registrationNumber", aircraft.getRegistrationNumber().number());
        AircraftModel model = aircraft.getAircraftModel();
        String modelName = model.getModelName().name();
        payload.put("modelCode", modelName);
        payload.put("modelName", modelName);
        payload.put("status", aircraft.getStatus().name());
        if (model.getMaximumRange() != null) payload.put("maximumRange", model.getMaximumRange());
        if (aircraft.getActualSeatingCapacity() != null) payload.put("seatingCapacity", aircraft.getActualSeatingCapacity());
        return envelope.toString();
    }

    private void publish(String type, String routingKey, Aircraft aircraft) {
        String eventId = UUID.randomUUID().toString();
        String body = toJson(type, eventId, aircraft);   // built now, with the state of this transaction
        Runnable send = () -> send(type, routingKey, eventId, body);
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
            log.warn("Aircraft event {} ({}) was not published: {}", eventId, routingKey, ex.getMessage());
        }
    }
}
