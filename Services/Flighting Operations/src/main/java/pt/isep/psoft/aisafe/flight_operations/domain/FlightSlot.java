package pt.isep.psoft.aisafe.flight_operations.domain;

import java.time.LocalDateTime;


public record FlightSlot(String flightId, LocalDateTime scheduledDateTime, FlightStatus status) {}
