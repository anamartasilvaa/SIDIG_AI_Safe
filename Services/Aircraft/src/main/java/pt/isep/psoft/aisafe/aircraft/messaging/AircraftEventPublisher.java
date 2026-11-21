package pt.isep.psoft.aisafe.aircraft.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.aircraft.domain.Aircraft;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class AircraftEventPublisher {
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final String exchange;

    public AircraftEventPublisher(RabbitTemplate rabbit, ObjectMapper mapper,
                                  @Value("${app.messaging.aircraft-exchange:aircraft.events}") String exchange) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.exchange = exchange;
    }

    public void aircraftChanged(Aircraft aircraft) {
        String eventId = UUID.randomUUID().toString();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", eventId);
        envelope.put("type", "AircraftChanged");
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("aggregateId", aircraft.getRegistrationNumber().number());
        envelope.put("revision", aircraft.getRevision());
        ObjectNode payload = envelope.putObject("payload");
        payload.put("registrationNumber", aircraft.getRegistrationNumber().number());
        payload.put("modelName", aircraft.getAircraftModel().getModelName().name());
        payload.put("status", aircraft.getStatus().name());
        try {
            rabbit.convertAndSend(exchange, "aircraft.changed", mapper.valueToTree(envelope).toString(), message -> {
            message.getMessageProperties().setContentType("application/json");
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setMessageId(eventId);
            message.getMessageProperties().setType("AircraftChanged");
            return message;
            });
        } catch (RuntimeException ex) {
            log.warn("Aircraft event {} could not be published; it will be retried by the broker when available",
                    eventId);
        }
    }
}
