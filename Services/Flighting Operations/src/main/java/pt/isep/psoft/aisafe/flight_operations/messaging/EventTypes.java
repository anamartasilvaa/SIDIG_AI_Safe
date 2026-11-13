package pt.isep.psoft.aisafe.flight_operations.messaging;

public final class EventTypes {

    public static final String FLIGHTS_EXCHANGE = "flights.events";
    public static final String AIRCRAFT_EXCHANGE = "aircraft.events";
    public static final String ROUTES_EXCHANGE = "routes.events";

    public static final String FLIGHT_SCHEDULED = "FlightScheduled";
    public static final String FLIGHT_STATUS_CHANGED = "FlightStatusChanged";
    public static final String FLIGHT_REASSIGNED = "FlightReassigned";
    public static final String GROUNDING_REQUESTED = "GroundingRequested";

    public static final String RK_FLIGHT_SCHEDULED = "flight.scheduled";
    public static final String RK_FLIGHT_STATUS_CHANGED = "flight.status-changed";
    public static final String RK_FLIGHT_REASSIGNED = "flight.reassigned";
    public static final String RK_GROUNDING_REQUESTED = "fo.grounding.requested";

    public static final int CURRENT_VERSION = 1;

    private EventTypes() {}
}