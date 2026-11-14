package pt.isep.psoft.aisafe.flight_operations.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "scheduled_flight", schema = "fo_command")
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

    @Column(length = 200)
    private String statusReason;

    @Column(nullable = false)
    private long revision;

    @Column(nullable = false)
    private Instant lastModifiedAt;

    /** Used by the deterministic conflict rule: the oldest (createdAt, flightId) wins. */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false, length = 60)
    private String originInstance;

    protected ScheduledFlight() {}

    public ScheduledFlight(RouteId routeId, RegistrationNumber aircraft, LocalDateTime scheduledDateTime,
                           LocalDateTime now) {
        this(routeId, aircraft, scheduledDateTime, now, "local");
    }

    public ScheduledFlight(RouteId routeId, RegistrationNumber aircraft, LocalDateTime scheduledDateTime,
                           LocalDateTime now, String originInstance) {
        if (routeId == null) throw new IllegalArgumentException("A route ID is required to schedule a flight.");
        if (aircraft == null) throw new IllegalArgumentException("An aircraft registration is required to schedule a flight.");
        if (scheduledDateTime == null) throw new IllegalArgumentException("The scheduled date and time are required.");
        if (!scheduledDateTime.isAfter(now)) throw new IllegalArgumentException("Cannot schedule a flight in the past.");
        if (originInstance == null || originInstance.isBlank()) throw new IllegalArgumentException("The origin instance is required.");

        this.flightId = FlightId.newId();
        this.routeId = routeId;
        this.aircraft = aircraft;
        this.scheduledDateTime = scheduledDateTime;
        this.status = FlightStatus.SCHEDULED;
        this.revision = 1;
        this.lastModifiedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = this.lastModifiedAt;
        this.originInstance = originInstance;
    }
    public static ScheduledFlight restore(FlightId flightId, RouteId routeId, RegistrationNumber aircraft,
                                          LocalDateTime scheduledDateTime, LocalDateTime actualDeparture,
                                          LocalDateTime actualArrival, FlightStatus status, String statusReason,
                                          long revision, Instant lastModifiedAt, Instant createdAt,
                                          String originInstance) {
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
        f.statusReason = statusReason;
        f.revision = revision;
        f.lastModifiedAt = lastModifiedAt;
        f.createdAt = createdAt != null ? createdAt : lastModifiedAt;
        f.originInstance = originInstance != null && !originInstance.isBlank() ? originInstance : "unknown";
        return f;
    }

    public static ScheduledFlight restore(FlightId flightId, RouteId routeId, RegistrationNumber aircraft,
                                          LocalDateTime scheduledDateTime, LocalDateTime actualDeparture,
                                          LocalDateTime actualArrival, FlightStatus status,
                                          long revision, Instant lastModifiedAt) {
        return restore(flightId, routeId, aircraft, scheduledDateTime, actualDeparture, actualArrival,
                status, null, revision, lastModifiedAt, lastModifiedAt, "unknown");
    }

    public void changeStatus(FlightStatus newStatus, LocalDateTime now) {
        changeStatus(newStatus, now, null);
    }

    public void changeStatus(FlightStatus newStatus, LocalDateTime now, String reason) {
        if (newStatus == null) throw new IllegalArgumentException("Status cannot be null.");
        if (newStatus == status) return;
        if (!status.canTransitionTo(newStatus)) {
            throw new SchedulingRuleViolation("Flight " + flightId + " cannot change from " + status
                    + " to " + newStatus + ". Allowed: " + status.allowedNext());
        }
        if (newStatus == FlightStatus.IN_FLIGHT) this.actualDepartureDateTime = now;
        if (newStatus == FlightStatus.COMPLETED) this.actualArrivalDateTime = now;
        this.status = newStatus;
        this.statusReason = reason;
        touch();
    }

    public void reassignAircraft(RegistrationNumber newAircraft) {
        if (newAircraft == null) throw new IllegalArgumentException("The new aircraft registration is required.");
        if (!canChangeAircraft()) {
            throw new SchedulingRuleViolation("Flight " + flightId + " is " + status + " and cannot change aircraft.");
        }
        if (newAircraft.equals(aircraft)) return;
        this.aircraft = newAircraft;
        touch();
    }

    public boolean canChangeAircraft() {
        return status == FlightStatus.SCHEDULED || status == FlightStatus.DELAYED;
    }

    public boolean mergeReplica(RegistrationNumber otherAircraft, FlightStatus otherStatus, String otherReason,
                                LocalDateTime otherDeparture, LocalDateTime otherArrival,
                                long otherRevision, Instant otherLastModifiedAt) {
        if (!isOlderThan(otherRevision, otherLastModifiedAt, otherStatus)) {
            return false;
        }
        if (otherAircraft != null) {
            this.aircraft = otherAircraft;
        }
        this.status = otherStatus;
        this.statusReason = otherReason;
        this.actualDepartureDateTime = otherDeparture;
        this.actualArrivalDateTime = otherArrival;
        this.revision = otherRevision;
        this.lastModifiedAt = otherLastModifiedAt;
        return true;
    }

    public boolean mergeReplica(RegistrationNumber otherAircraft, FlightStatus otherStatus, LocalDateTime otherDeparture,
                                LocalDateTime otherArrival, long otherRevision, Instant otherLastModifiedAt) {
        return mergeReplica(otherAircraft, otherStatus, null, otherDeparture, otherArrival, otherRevision, otherLastModifiedAt);
    }

    public boolean mergeReplica(FlightStatus otherStatus, LocalDateTime otherDeparture, LocalDateTime otherArrival,
                                long otherRevision, Instant otherLastModifiedAt) {
        return mergeReplica(null, otherStatus, null, otherDeparture, otherArrival, otherRevision, otherLastModifiedAt);
    }

    public boolean winsConflictAgainst(ScheduledFlight other) {
        int byCreation = createdAt.compareTo(other.createdAt);
        if (byCreation != 0) return byCreation < 0;
        return flightId.value().compareTo(other.flightId.value()) < 0;
    }

    private void touch() {
        this.revision++;
        this.lastModifiedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
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
    public String getStatusReason() { return statusReason; }
    public long getRevision() { return revision; }
    public Instant getLastModifiedAt() { return lastModifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getOriginInstance() { return originInstance; }
}