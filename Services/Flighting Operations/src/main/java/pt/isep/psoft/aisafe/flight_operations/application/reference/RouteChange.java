package pt.isep.psoft.aisafe.flight_operations.application.reference;

import java.time.Instant;

public record RouteChange(
        String routeId,
        String origin,
        String destination,
        Boolean active,
        Double minimumRange,
        Integer minimumCapacity,
        Instant changedAt) {
}