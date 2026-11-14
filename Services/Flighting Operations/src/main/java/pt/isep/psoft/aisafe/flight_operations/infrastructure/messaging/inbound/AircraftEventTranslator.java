package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.reference.AircraftChange;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.EventTypes;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Anti-Corruption Layer for the Aircraft Service events. Translates the agreed envelope
 * {eventId, type, occurredAt, aggregateId, revision,
 *  payload: {registrationNumber, modelCode, status, maximumRange, seatingCapacity}}
 * to the Flight Operations model. Tolerant: unknown fields are ignored, the payload may also
 * come as JSON text, and the registration may come only in aggregateId.
 */
@Component
public class AircraftEventTranslator {

    private final ObjectMapper mapper;

    public AircraftEventTranslator(ObjectMapper eventMapper) {
        this.mapper = eventMapper;
    }

    /** @throws IllegalArgumentException if the message cannot be understood (goes to the DLQ). */
    public AircraftChange translate(JsonNode envelope, Instant receivedAt) {
        JsonNode payload = payload(envelope);
        String registration = firstText(payload, "registrationNumber", "registration");
        if (registration == null) {
            registration = text(envelope, "aggregateId");
        }
        if (registration == null) {
            throw new IllegalArgumentException("Aircraft event without registration number.");
        }
        return new AircraftChange(
                registration,
                text(payload, "modelCode"),
                text(payload, "status"),
                number(payload, "maximumRange"),
                integer(payload, "seatingCapacity"),
                envelope.path("revision").asLong(0),
                instant(envelope, receivedAt));
    }

    /** True for the GroundingRequested event published by Flight Operations itself. */
    public boolean isGroundingRequest(JsonNode envelope) {
        return EventTypes.GROUNDING_REQUESTED.equals(text(envelope, "type"));
    }

    /** Event id used for idempotency: eventId of the envelope, else the AMQP messageId. */
    public String eventId(JsonNode envelope, String messageId) {
        String id = text(envelope, "eventId");
        if (id == null) {
            id = (messageId == null || messageId.isBlank()) ? null : messageId;
        }
        if (id == null) {
            throw new IllegalArgumentException("Aircraft event without eventId or messageId.");
        }
        return id;
    }

    private JsonNode payload(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        if (payload.isTextual()) {
            try {
                return mapper.readTree(payload.asText());
            } catch (Exception e) {
                throw new IllegalArgumentException("Aircraft event with an invalid payload.", e);
            }
        }
        return payload;
    }

    private static Instant instant(JsonNode envelope, Instant fallback) {
        String value = text(envelope, "occurredAt");
        if (value == null) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Aircraft event with an invalid occurredAt: " + value, e);
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }
}
