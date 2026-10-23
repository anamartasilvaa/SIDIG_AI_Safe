package pt.isep.psoft.aisafe.flight_operations.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "scheduled_flight")
public class ScheduledFlight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Version
    private Long version;

    @Embedded
    private FlightId flightId;

    @Embedded
    private RouteId routeId;

    @Embedded
    private RegistrationNumber aircraft;

    @Column(nullable = false)
    private LocalDateTime scheduledDateTime;

    private LocalDateTime actualDepartureDateTime;
    private LocalDateTime actualArrivalDateTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStatus status;

    @Column(nullable = false)
    private long revision;

    @Column(nullable = false)
    private Instant lastModifiedAt;

    protected ScheduledFlight() {}


    public ScheduledFlight(RouteId routeId, RegistrationNumber aircraft, LocalDateTime scheduledDateTime,
                           LocalDateTime now) {
        if (routeId == null) throw new IllegalArgumentException("A route ID is required to schedule a flight.");
        if (aircraft == null) throw new IllegalArgumentException("An aircraft registration is required to schedule a flight.");
        if (scheduledDateTime == null) throw new IllegalArgumentException("The scheduled date and time are required.");
        if (!scheduledDateTime.isAfter(now)) throw new IllegalArgumentException("Cannot schedule a flight in the past.");

        this.flightId = FlightId.newId();
        this.routeId = routeId;
        this.aircraft = aircraft;
        this.scheduledDateTime = scheduledDateTime;
        this.status = FlightStatus.SCHEDULED;
        this.revision = 1;
        this.lastModifiedAt = Instant.now();
    }

    public static ScheduledFlight restore(FlightId flightId, RouteId routeId, RegistrationNumber aircraft,
                                          LocalDateTime scheduledDateTime, LocalDateTime actualDeparture,
                                          LocalDateTime actualArrival, FlightStatus status,
                                          long revision, Instant lastModifiedAt) {
        if (flightId == null || routeId == null || aircraft == null || scheduledDateTime == null
                || status == null || lastModifiedAt == null) {
            throw new IllegalArgumentException("Incomplete flight replica.");
        }
        ScheduledFlight f = new ScheduledFlight();
        f.flightId = flightId;
        f.routeId = routeId;
        f.aircraft = aircraft;
        f.scheduledDateTime = scheduledDateTime;
        f.actualDepartureDateTime = actualDeparture;
        f.actualArrivalDateTime = actualArrival;
        f.status = status;
        f.revision = revision;
        f.lastModifiedAt = lastModifiedAt;
        return f;
    }
    public void changeStatus(FlightStatus newStatus, LocalDateTime now) {
        if (newStatus == null) throw new IllegalArgumentException("Status cannot be null.");
        if (newStatus == status) return;
        if (!status.canTransitionTo(newStatus)) {
            throw new SchedulingRuleViolation("Flight " + flightId + " cannot change from " + status
                    + " to " + newStatus + ". Allowed: " + status.allowedNext());
        }
        if (newStatus == FlightStatus.IN_FLIGHT) this.actualDepartureDateTime = now;
        if (newStatus == FlightStatus.COMPLETED) this.actualArrivalDateTime = now;
        this.status = newStatus;
        this.revision++;
        this.lastModifiedAt = Instant.now();
    }

    public boolean mergeReplica(FlightStatus otherStatus, LocalDateTime otherDeparture, LocalDateTime otherArrival,
                                long otherRevision, Instant otherLastModifiedAt) {
        if (!isOlderThan(otherRevision, otherLastModifiedAt, otherStatus)) {
            return false;
        }
        this.status = otherStatus;
        this.actualDepartureDateTime = otherDeparture;
        this.actualArrivalDateTime = otherArrival;
        this.revision = otherRevision;
        this.lastModifiedAt = otherLastModifiedAt;
        return true;
    }

    private boolean isOlderThan(long otherRevision, Instant otherLastModifiedAt, FlightStatus otherStatus) {
        if (otherRevision != revision) return otherRevision > revision;
        int byTime = otherLastModifiedAt.compareTo(lastModifiedAt);
        if (byTime != 0) return byTime > 0;
        return otherStatus.ordinal() > status.ordinal();
    }

    public FlightSlot toSlot() {
        return new FlightSlot(flightId.value(), scheduledDateTime, status);
    }

    public Long getPk() { return pk; }
    public FlightId getFlightId() { return flightId; }
    public RouteId getRouteId() { return routeId; }
    public RegistrationNumber getAircraft() { return aircraft; }
    public LocalDateTime getScheduledDateTime() { return scheduledDateTime; }
    public LocalDateTime getActualDepartureDateTime() { return actualDepartureDateTime; }
    public LocalDateTime getActualArrivalDateTime() { return actualArrivalDateTime; }
    public FlightStatus getStatus() { return status; }
    public long getRevision() { return revision; }
    public Instant getLastModifiedAt() { return lastModifiedAt; }
}
