package pt.isep.psoft.aisafe.aircraft.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.Assert;
import pt.isep.psoft.aisafe.aircraft.domain.AircraftModel;
import pt.isep.psoft.aisafe.aircraft.domain.AircraftStatus;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

@Entity
public class Aircraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;
    @Getter
    @Column(nullable = false, unique = true, updatable = false)
    private String globalId = UUID.randomUUID().toString();
    @Getter
    @Column(nullable = false)
    private long revision;
    @Getter
    @Column(nullable = false)
    private Instant lastModified = Instant.now();

    @Version
    private Long version;

    @Getter
    @Embedded
    @AttributeOverride(name = "number", column = @Column(name = "registration_number", unique = true, nullable = false))
    private RegistrationNumber registrationNumber;

    @Getter
    @ManyToOne(optional = false)
    @JoinColumn(name = "aircraft_model_pk", nullable = false)
    private AircraftModel model;

    @Getter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AircraftStatus status;

    @Getter
    @Column(nullable = false)
    private LocalDate manufacturingDate;

    @Getter
    @Column(nullable = false)
    private Integer actualSeatingCapacity;

    @Getter
    @Column(nullable = false)
    private Double totalFlightHours;

    @Getter
    @Column(nullable = true)
    private String features;

    /* keep track of assignments locally */
    @Getter
    @Setter
    @Column(name = "total_assignments")
    private Integer totalAssignments = 0;

    protected Aircraft() {}

    public Aircraft(RegistrationNumber registrationNumber, AircraftModel model,
                    LocalDate manufacturingDate, Integer actualSeatingCapacity, String features) {

        Assert.notNull(registrationNumber, "Registration number must not be null.");
        Assert.notNull(model, "Aircraft model must not be null.");
        Assert.notNull(manufacturingDate, "Manufacturing date must not be null.");
        Assert.isTrue(actualSeatingCapacity != null && actualSeatingCapacity > 0,
                "Actual seating capacity must be strictly positive.");

        this.registrationNumber = registrationNumber;
        this.model = model;
        this.manufacturingDate = manufacturingDate;
        this.actualSeatingCapacity = actualSeatingCapacity;
        this.features = features;

        this.status = AircraftStatus.ACTIVE;
        this.totalFlightHours = 0.0;
    }

    // increment assignments (called when Flights Service assigns this aircraft)
    public void incrementAssignments() {
        if (this.totalAssignments == null) {
            this.totalAssignments = 0;
        }
        this.totalAssignments++;
    }

    public void updateStatus(AircraftStatus newStatus) {
        Assert.notNull(newStatus, "New status must not be null.");
        this.status = newStatus;
        touch();
    }

    public void addFlightHours(Double hours) {
        Assert.isTrue(hours != null && hours > 0, "Flight hours to add must be positive.");
        this.totalFlightHours += hours;
        touch();
    }
    public void touch() { revision++; lastModified = Instant.now(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Aircraft aircraft = (Aircraft) o;
        return Objects.equals(registrationNumber, aircraft.registrationNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(registrationNumber);
    }

    // --- GETTERS ---

    public AircraftModel getAircraftModel() {
        return this.model;
    }
}
