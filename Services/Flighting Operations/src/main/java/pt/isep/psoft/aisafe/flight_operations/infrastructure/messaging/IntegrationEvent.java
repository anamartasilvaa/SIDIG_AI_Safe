package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IntegrationEvent(
        String eventId,
        String type,
        int version,
        Instant occurredAt,
        String aggregateId,
        long revision,
        String sourceInstance,
        String correlationId,
        JsonNode payload) {
}