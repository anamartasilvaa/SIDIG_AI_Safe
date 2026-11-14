package pt.isep.psoft.aisafe.flight_operations.application.query;

import jakarta.persistence.*;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "flight_view", schema = "fo_query")
public class FlightView {

    @Id
    @Column(name = "flight_id", length = 36)
    private String flightId;

    @Column(name = "route_id", nullable = false, length = 40)
    private String routeId;

    @Column(length = 10)
    private String origin;

    @Column(length = 10)
    private String destination;

    @Column(name = "registration_number", nullable = false, length = 20)
    private String registrationNumber;

    @Column(name = "scheduled_date_time", nullable = false)
    private LocalDateTime scheduledDateTime;

    @Column(name = "actual_departure")
    private LocalDateTime actualDeparture;

    @Column(name = "actual_arrival")
    private LocalDateTime actualArrival;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "status_reason", length = 200)
    private String statusReason;

    @Column(nullable = false)
    private long revision;

    @Column(name = "last_modified_at", nullable = false)
    private Instant lastModifiedAt;

    protected FlightView() {}

    public static FlightView from(FlightState s, String origin, String destination) {
        FlightView v = new FlightView();
        v.flightId = s.flightId();
        v.routeId = s.routeId();
        v.copy(s, origin, destination);
        return v;
    }

    public boolean applyIfNewer(FlightState s, String origin, String destination) {
        if (!isNewer(s)) {
            return false;
        }
        copy(s, origin, destination);
        return true;
    }

    private boolean isNewer(FlightState s) {
        if (s.revision() != revision) return s.revision() > revision;
        int byTime = s.lastModifiedAt().compareTo(lastModifiedAt);
        if (byTime != 0) return byTime > 0;
        return FlightStatus.parse(s.status()).ordinal() > FlightStatus.parse(status).ordinal();
    }

    private void copy(FlightState s, String origin, String destination) {
        if (origin != null) this.origin = origin;
        if (destination != null) this.destination = destination;
        this.registrationNumber = s.registrationNumber();
        this.scheduledDateTime = s.scheduledDateTime();
        this.actualDeparture = s.actualDepartureDateTime();
        this.actualArrival = s.actualArrivalDateTime();
        this.status = s.status();
        this.statusReason = s.statusReason();
        this.revision = s.revision();
        this.lastModifiedAt = s.lastModifiedAt();
    }

    public String getFlightId() { return flightId; }
    public String getRouteId() { return routeId; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public String getRegistrationNumber() { return registrationNumber; }
    public LocalDateTime getScheduledDateTime() { return scheduledDateTime; }
    public LocalDateTime getActualDeparture() { return actualDeparture; }
    public LocalDateTime getActualArrival() { return actualArrival; }
    public String getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public long getRevision() { return revision; }
    public Instant getLastModifiedAt() { return lastModifiedAt; }
}