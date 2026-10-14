package pt.isep.psoft.aisafe.flight_operations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;
import java.util.UUID;

@Embeddable
public class FlightId {

    @Column(name = "flight_id", length = 36, nullable = false, unique = true, updatable = false)
    private String uuid;

    protected FlightId() {}

    private FlightId(String uuid) {
        this.uuid = uuid;
    }

    public static FlightId newId() {
        return new FlightId(UUID.randomUUID().toString());
    }

    public static FlightId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Flight id is required.");
        }
        try {
            return new FlightId(UUID.fromString(value.trim()).toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid flight id '" + value + "' (expected a UUID).");
        }
    }

    public String value() { return uuid; }

    @Override public boolean equals(Object o) {
        return o instanceof FlightId other && Objects.equals(uuid, other.uuid);
    }
    @Override public int hashCode() { return Objects.hashCode(uuid); }
    @Override public String toString() { return uuid; }
}
