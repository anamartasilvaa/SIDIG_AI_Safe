package pt.isep.psoft.aisafe.flight_operations.domain.reference;

import jakarta.persistence.*;
import pt.isep.psoft.aisafe.flight_operations.domain.RouteRequirements;

import java.time.Instant;
import java.util.Locale;
@Entity
@Table(name = "route_ref", schema = "fo_command")
public class RouteRef {

    @Id
    @Column(name = "route_id", length = 40)
    private String routeId;

    @Column(length = 10)
    private String origin;

    @Column(length = 10)
    private String destination;

    @Column(nullable = false)
    private boolean active;

    private Double distance;

    @Column(name = "minimum_range")
    private Double minimumRange;

    @Column(name = "minimum_capacity")
    private Integer minimumCapacity;

    @Column(nullable = false)
    private long revision;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RouteRef() {}

    public static RouteRef create(String routeId, String origin, String destination, boolean active,
                                  Double distance, Double minimumRange, Integer minimumCapacity,
                                  long revision, Instant changedAt) {
        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("Route id is required.");
        }
        if (changedAt == null) {
            throw new IllegalArgumentException("The time of the change is required.");
        }
        RouteRef r = new RouteRef();
        r.routeId = routeId.trim().toUpperCase(Locale.ROOT);
        r.origin = origin;
        r.destination = destination;
        r.active = active;
        r.distance = distance;
        r.minimumRange = minimumRange;
        r.minimumCapacity = minimumCapacity;
        r.revision = Math.max(revision, 0);
        r.updatedAt = changedAt;
        return r;
    }

    public boolean applyIfNewer(String newOrigin, String newDestination, Boolean newActive, Double newDistance,
                                Double newMinimumRange, Integer newMinimumCapacity,
                                long newRevision, Instant changedAt) {
        if (!ReferenceVersion.isNewer(newRevision, changedAt, revision, updatedAt)) {
            return false;
        }
        if (newOrigin != null) this.origin = newOrigin;
        if (newDestination != null) this.destination = newDestination;
        if (newActive != null) this.active = newActive;
        if (newDistance != null) this.distance = newDistance;
        if (newMinimumRange != null) this.minimumRange = newMinimumRange;
        if (newMinimumCapacity != null) this.minimumCapacity = newMinimumCapacity;
        if (newRevision > 0) this.revision = newRevision;
        this.updatedAt = changedAt;
        return true;
    }
    public RouteRequirements toRequirements() {
        return new RouteRequirements(routeId, active, distance, minimumRange, minimumCapacity);
    }

    public String getRouteId() { return routeId; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public boolean isActive() { return active; }
    public Double getDistance() { return distance; }
    public Double getMinimumRange() { return minimumRange; }
    public Integer getMinimumCapacity() { return minimumCapacity; }
    public long getRevision() { return revision; }
    public Instant getUpdatedAt() { return updatedAt; }
}