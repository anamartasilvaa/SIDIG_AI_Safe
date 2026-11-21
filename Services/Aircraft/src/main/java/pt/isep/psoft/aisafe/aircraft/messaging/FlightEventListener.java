package pt.isep.psoft.aisafe.aircraft.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.psoft.aisafe.aircraft.application.AircraftService;

import java.nio.charset.StandardCharsets;

@Component
public class FlightEventListener {
    private final AircraftService aircrafts;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper;

    public FlightEventListener(AircraftService aircrafts, IdempotencyGuard idempotency,
                               TransactionTemplate transactionTemplate, ObjectMapper eventMapper) {
        this.aircrafts = aircrafts;
        this.idempotency = idempotency;
        this.transactionTemplate = transactionTemplate;
        this.mapper = eventMapper;
    }

    @RabbitListener(queues = "#{aircraftFlightsQueue.name}")
    public void onMessage(Message message) {
        try {
            JsonNode event = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            JsonNode payloadNode = event.path("payload");
            final JsonNode payload = payloadNode.isMissingNode() ? event : payloadNode;
            final JsonNode flight = payload.has("flight") ? payload.get("flight") : payload;
            final String eventId = event.path("eventId").asText(message.getMessageProperties().getMessageId());
            final String registration = flight.path("registrationNumber").asText(
                    flight.path("registration").asText(payload.path("registrationNumber").asText()));
            final String type = event.path("type").asText(event.path("eventType").asText());
            final String status = flight.path("status").asText(payload.path("status").asText(""));
            transactionTemplate.executeWithoutResult(tx -> {
                if (!idempotency.firstTime("aircraft.flight", eventId)) return;
                if ("FlightScheduled".equals(type) || "FlightReassigned".equals(type)
                        || "FlightStatusChanged".equals(type) && !"COMPLETED".equalsIgnoreCase(status)) {
                    aircrafts.applyFlightAssignment(registration);
                }
                if ("COMPLETED".equalsIgnoreCase(status)) {
                    double hours = flight.path("flightHours").isNumber()
                            ? flight.path("flightHours").asDouble()
                            : flight.path("durationHours").asDouble(payload.path("flightHours").asDouble(0));
                    if (hours > 0) aircrafts.applyFlightCompletion(registration, hours);
                }
            });
        } catch (Exception ex) {
            throw new AmqpRejectAndDontRequeueException("Malformed flight event", ex);
        }
    }
}
