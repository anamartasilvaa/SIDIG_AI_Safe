package pt.isep.psoft.aisafe.flight_operations.domain;

import jakarta.persistence.*;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
public class ScheduledFlight {

    /*
     * PASSO 2 - ID globalmente unico.
     * Com IDENTITY cada instancia comecava em 1, por isso o voo 1 da replica A
     * e o voo 1 da replica B eram voos diferentes com o mesmo id.
     * Um UUID gerado no dominio e unico em todas as replicas, sem coordenacao.
     */
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

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

        this.id = UUID.randomUUID().toString();
        this.routeId = routeId;
        this.aircraftRegistration = aircraftRegistration;
        this.scheduledDateTime = scheduledDateTime;
        this.status = FlightStatus.SCHEDULED;
    }

    public void updateStatus(FlightStatus newStatus) {
        Assert.notNull(newStatus, "Status cannot be null.");
        this.status = newStatus;
    }

    public void changeAircraft(String newAircraftRegistration) {
        Assert.hasText(newAircraftRegistration, "Replacement aircraft registration cannot be empty.");
        this.aircraftRegistration = newAircraftRegistration;
    }

    public String getId() { return id; }
    public String getRouteId() { return routeId; }
    public String getAircraftRegistration() { return aircraftRegistration; }
    public LocalDateTime getScheduledDateTime() { return scheduledDateTime; }
    public FlightStatus getStatus() { return status; }
}
