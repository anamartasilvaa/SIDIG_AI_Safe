package pt.isep.psoft.aisafe.airports_routes_service.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.SnapshotDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;

/**
 * Endpoint interno (so ROLE_PEER; a gateway bloqueia /internal/): devolve uma copia de todos os
 * aeroportos e rotas desta instancia. Usado pelo SnapshotBootstrapper de uma instancia que arranca
 * com a base de dados vazia.
 */
@RestController
@RequestMapping("/internal")
public class InternalSnapshotController {

    private final AirportService airportService;
    private final RouteService routeService;

    public InternalSnapshotController(AirportService airportService, RouteService routeService) {
        this.airportService = airportService;
        this.routeService = routeService;
    }

    @GetMapping("/snapshot")
    public SnapshotDTO snapshot() {
        return new SnapshotDTO(airportService.exportSnapshot(), routeService.exportSnapshot());
    }
}
