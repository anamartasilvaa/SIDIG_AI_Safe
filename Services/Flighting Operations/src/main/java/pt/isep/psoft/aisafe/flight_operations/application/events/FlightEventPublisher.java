package pt.isep.psoft.aisafe.flight_operations.application.events;

import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

public interface FlightEventPublisher {

    void flightScheduled(ScheduledFlight flight, String correlationId);

    void flightStatusChanged(ScheduledFlight flight, String previousStatus, String correlationId);

    void flightReassigned(ScheduledFlight flight, String fromAircraft, String correlationId);

    void groundingRequested(String registrationNumber, String correlationId);
}