package pt.isep.psoft.aisafe.flight_operations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Locale;
import java.util.Objects;

@Embeddable
public class RegistrationNumber {

    @Column(name = "aircraft_registration", nullable = false)
    private String number;

    protected RegistrationNumber() {}

    private RegistrationNumber(String number) {
        this.number = number;
    }

    public static RegistrationNumber of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An aircraft registration is required to schedule a flight.");
        }
        return new RegistrationNumber(value.trim().toUpperCase(Locale.ROOT));
    }

    public String value() { return number; }

    @Override public boolean equals(Object o) {
        return o instanceof RegistrationNumber other && Objects.equals(number, other.number);
    }
    @Override public int hashCode() { return Objects.hashCode(number); }
    @Override public String toString() { return number; }
}
