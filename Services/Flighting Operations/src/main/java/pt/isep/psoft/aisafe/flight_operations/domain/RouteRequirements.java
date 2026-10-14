package pt.isep.psoft.aisafe.flight_operations.domain;

public record RouteRequirements(
        String routeId,
        boolean active,
        Double distance,
        Double minimumRange,
        Integer minimumCapacity
) {
    public Double requiredRange() {
        return minimumRange != null ? minimumRange : distance;
    }
}
