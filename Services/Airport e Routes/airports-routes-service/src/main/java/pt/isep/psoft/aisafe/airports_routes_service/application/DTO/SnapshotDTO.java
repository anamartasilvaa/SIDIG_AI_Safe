package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

import java.util.List;

/** Copia de todos os aeroportos e rotas de uma instancia (GET /internal/snapshot). */
public record SnapshotDTO(List<AirportSnapshotDTO> airports, List<RouteSnapshotDTO> routes) {
}
