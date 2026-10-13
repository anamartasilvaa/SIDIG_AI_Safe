package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import java.util.List;

/**
 * Resultado de uma consulta agregada (local + peers).
 * unreachablePeers != vazio significa resposta PARCIAL: o cliente recebe o que
 * foi possivel obter (disponibilidade > consistencia, como pede o CAP do enunciado).
 */
public record AggregatedResult<T>(List<T> items, List<String> unreachablePeers) {
    public boolean isPartial() { return !unreachablePeers.isEmpty(); }
}
