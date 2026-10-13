package pt.isep.psoft.aisafe.flight_operations.domain;

import jakarta.persistence.*;
import org.springframework.util.Assert;
import java.time.LocalDateTime;

@Entity
public class ScheduledFlight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Version
    private Long version;

    @Column(nullable = false)
    private LocalDateTime scheduledDateTime;

    private LocalDateTime actualDepartureDateTime;
    private LocalDateTime actualArrivalDateTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStatus status;

    @Column(nullable = false)
    private String routeId;

    @Column(nullable = false)
    private String aircraftRegistration;

    protected ScheduledFlight() {}


    public ScheduledFlight(String routeId, String aircraftRegistration, LocalDateTime scheduledDateTime) {
        Assert.hasText(routeId, "A route ID is required to schedule a flight.");
        Assert.hasText(aircraftRegistration, "An aircraft registration is required to schedule a flight.");
        Assert.notNull(scheduledDateTime, "The scheduled date and time are required.");
        Assert.isTrue(scheduledDateTime.isAfter(LocalDateTime.now()), "Cannot schedule a flight in the past.");

        this.routeId = routeId;
        this.aircraftRegistration = aircraftRegistration;
        this.scheduledDateTime = scheduledDateTime;
        this.status = FlightStatus.SCHEDULED;
    }

    public void updateStatus(FlightStatus newStatus) {
        this.status = newStatus;
    }

    public void changeAircraft(String newAircraftRegistration) {
        Assert.hasText(newAircraftRegistration, "Replacement aircraft registration cannot be empty.");
        this.aircraftRegistration = newAircraftRegistration;
    }

    // GETTERS
    public Long getPk() { return pk; }
    public String getRouteId() { return routeId; }
    public String getAircraftRegistration() { return aircraftRegistration; }
    public LocalDateTime getScheduledDateTime() { return scheduledDateTime; }
    public FlightStatus getStatus() { return status; }
}