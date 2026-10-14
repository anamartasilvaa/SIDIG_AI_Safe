package pt.isep.psoft.aisafe.flight_operations.domain;

public record AircraftCapabilities(
        String registrationNumber,
        String status,
        Double maximumRange,
        Integer seatingCapacity
) {
    public boolean isOperational() {
        return status == null || "ACTIVE".equalsIgnoreCase(status);
    }
}
