package pt.isep.psoft.aisafe.maintenance.application.DTO;

import java.util.List;

public record AggregatedResult<T>(List<T> items, List<String> unreachablePeers) {
    public AggregatedResult {
        items = List.copyOf(items);
        unreachablePeers = List.copyOf(unreachablePeers);
    }
}
