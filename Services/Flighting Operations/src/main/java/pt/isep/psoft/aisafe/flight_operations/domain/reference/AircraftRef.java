package pt.isep.psoft.aisafe.flight_operations.domain.reference;

import jakarta.persistence.*;
import pt.isep.psoft.aisafe.flight_operations.domain.AircraftCapabilities;

import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "aircraft_ref", schema = "fo_command")
public class AircraftRef {

    public static final String ACTIVE = "ACTIVE";

    @Id
    @Column(name = "registration_number", length = 20)
    private String registrationNumber;

    @Column(name = "model_code", length = 40)
    private String modelCode;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "maximum_range")
    private Double maximumRange;

    @Column(name = "seating_capacity")
    private Integer seatingCapacity;

    @Column(nullable = false)
    private long revision;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AircraftRef() {}

    public static AircraftRef create(String registrationNumber, String modelCode, String status,
                                     Double maximumRange, Integer seatingCapacity,
                                     long revision, Instant changedAt) {
        if (registrationNumber == null || registrationNumber.isBlank()) {
            throw new IllegalArgumentException("Aircraft registration is required.");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Aircraft status is required.");
        }
        if (changedAt == null) {
            throw new IllegalArgumentException("The time of the change is required.");
        }
        AircraftRef a = new AircraftRef();
        a.registrationNumber = normalise(registrationNumber);
        a.modelCode = modelCode;
        a.status = normalise(status);
        a.maximumRange = maximumRange;
        a.seatingCapacity = seatingCapacity;
        a.revision = Math.max(revision, 0);
        a.updatedAt = changedAt;
        return a;
    }

    public boolean applyIfNewer(String newModelCode, String newStatus, Double newRange, Integer newSeats,
                                long newRevision, Instant changedAt) {
        if (!ReferenceVersion.isNewer(newRevision, changedAt, revision, updatedAt)) {
            return false;
        }
        if (newModelCode != null) this.modelCode = newModelCode;
        if (newStatus != null && !newStatus.isBlank()) this.status = normalise(newStatus);
        if (newRange != null) this.maximumRange = newRange;
        if (newSeats != null) this.seatingCapacity = newSeats;
        if (newRevision > 0) this.revision = newRevision;
        this.updatedAt = changedAt;
        return true;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public AircraftCapabilities toCapabilities() {
        return new AircraftCapabilities(registrationNumber, status, maximumRange, seatingCapacity);
    }

    private static String normalise(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public String getRegistrationNumber() { return registrationNumber; }
    public String getModelCode() { return modelCode; }
    public String getStatus() { return status; }
    public Double getMaximumRange() { return maximumRange; }
    public Integer getSeatingCapacity() { return seatingCapacity; }
    public long getRevision() { return revision; }
    public Instant getUpdatedAt() { return updatedAt; }
}