package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.reference.RouteChange;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

@Component
public class AirportsRoutesTranslator {

    private final ObjectMapper mapper;

    public AirportsRoutesTranslator(ObjectMapper eventMapper) {
        this.mapper = eventMapper;
    }

    public Optional<RouteChange> translate(JsonNode envelope, Instant receivedAt) {
        String type = text(envelope, "eventType");
        if (type == null || !type.startsWith("ROUTE_")) {
            return Optional.empty();
        }
        Instant changedAt = parseInstant(text(envelope, "lastModified"), receivedAt);
        String pathId = resourceId(text(envelope, "path"));

        return switch (type) {
            case "ROUTE_CREATED" -> {
                JsonNode body = payload(envelope);
                String routeId = required(text(body, "routeId"), "routeId");
                yield Optional.of(new RouteChange(upper(routeId), upper(text(body, "originIata")),
                        upper(text(body, "destinationIata")), true,
                        number(body, "minimumRange"), integer(body, "minimumCapacity"), changedAt));
            }
            case "ROUTE_UPDATED" -> {
                JsonNode body = payload(envelope);
                yield Optional.of(new RouteChange(upper(required(pathId, "route id in path")), null, null, null,
                        number(body, "minimumRange"), integer(body, "minimumCapacity"), changedAt));
            }
            case "ROUTE_DEACTIVATED" -> Optional.of(new RouteChange(upper(required(pathId, "route id in path")),
                    null, null, false, null, null, changedAt));
            default -> Optional.empty();
        };
    }

    private JsonNode payload(JsonNode envelope) {
        String raw = text(envelope, "payload");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Route event without payload.");
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid route payload: " + e.getMessage(), e);
        }
    }

    static String resourceId(String path) {
        if (path == null) return null;
        String[] parts = path.split("/");
        return parts.length > 3 && !parts[3].isBlank() ? parts[3] : null;
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Route event without " + what + ".");
        }
        return value;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asDouble();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }
}