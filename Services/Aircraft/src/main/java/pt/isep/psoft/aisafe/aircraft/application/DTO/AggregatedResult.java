package pt.isep.psoft.aisafe.aircraft.application.DTO;

import java.util.List;

public record AggregatedResult<T>(List<T> items, List<String> unreachablePeers) {
    public boolean isPartial() {
        return !unreachablePeers.isEmpty();
    }
}
