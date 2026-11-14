package pt.isep.psoft.aisafe.flight_operations.application.query;

import jakarta.persistence.*;
@Entity
@Table(name = "route_utilization_view", schema = "fo_query")
public class RouteUtilizationView {

    @Id
    @Column(name = "route_id", length = 40)
    private String routeId;

    @Column(length = 10)
    private String origin;

    @Column(length = 10)
    private String destination;

    @Column(name = "flight_count", nullable = false)
    private long flightCount;

    protected RouteUtilizationView() {}

    public RouteUtilizationView(String routeId) {
        this.routeId = routeId;
    }

    public void update(long flightCount, String origin, String destination) {
        this.flightCount = flightCount;
        if (origin != null) this.origin = origin;
        if (destination != null) this.destination = destination;
    }

    public String getRouteId() { return routeId; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public long getFlightCount() { return flightCount; }
}