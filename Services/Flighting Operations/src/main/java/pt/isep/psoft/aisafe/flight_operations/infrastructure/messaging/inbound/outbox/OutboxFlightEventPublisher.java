package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound.outbox;

import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightEventPublisher;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

import java.util.Map;

import static pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.EventTypes.*;

@Component
public class OutboxFlightEventPublisher implements FlightEventPublisher {

    private final OutboxWriter outbox;

    public OutboxFlightEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Override
    public void flightScheduled(ScheduledFlight flight, String correlationId) {
        outbox.append(FLIGHTS_EXCHANGE, RK_FLIGHT_SCHEDULED, FLIGHT_SCHEDULED,
                flight.getFlightId().value(), flight.getRevision(), FlightState.of(flight), correlationId);
    }

    @Override
    public void flightStatusChanged(ScheduledFlight flight, String previousStatus, String correlationId) {
        outbox.append(FLIGHTS_EXCHANGE, RK_FLIGHT_STATUS_CHANGED, FLIGHT_STATUS_CHANGED,
                flight.getFlightId().value(), flight.getRevision(),
                Map.of("previousStatus", previousStatus, "flight", FlightState.of(flight)), correlationId);
    }

    @Override
    public void flightReassigned(ScheduledFlight flight, String fromAircraft, String correlationId) {
        outbox.append(FLIGHTS_EXCHANGE, RK_FLIGHT_REASSIGNED, FLIGHT_REASSIGNED,
                flight.getFlightId().value(), flight.getRevision(),
                Map.of("fromAircraft", fromAircraft, "flight", FlightState.of(flight)), correlationId);
    }

    @Override
    public void groundingRequested(String registrationNumber, String correlationId) {
        outbox.append(FLIGHTS_EXCHANGE, RK_GROUNDING_REQUESTED, GROUNDING_REQUESTED,
                registrationNumber, 0, Map.of("registrationNumber", registrationNumber), correlationId);
    }
}