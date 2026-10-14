package pt.isep.psoft.aisafe.flight_operations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Locale;
import java.util.Objects;

@Embeddable
public class RouteId {

    @Column(name = "route_id", nullable = false)
    private String code;

    protected RouteId() {}

    private RouteId(String code) {
        this.code = code;
    }

    public static RouteId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A route ID is required to schedule a flight.");
        }
        return new RouteId(value.trim().toUpperCase(Locale.ROOT));
    }

    public String value() { return code; }

    @Override public boolean equals(Object o) {
        return o instanceof RouteId other && Objects.equals(code, other.code);
    }
    @Override public int hashCode() { return Objects.hashCode(code); }
    @Override public String toString() { return code; }
}
