package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import java.util.List;
public record ScheduleResultDTO(ScheduledFlightViewDTO flight, List<String> unreachablePeers) {
    public boolean isPartial() { return !unreachablePeers.isEmpty(); }
}
