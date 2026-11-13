package pt.isep.psoft.aisafe.airports_routes_service.bootstrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;

/**
 * Ao arrancar, reconstroi o modelo de leitura a partir do modelo de escrita.
 * Cobre dados que ja existiam antes do modelo de leitura (por exemplo a base H2 em ficheiro)
 * e mostra que o modelo de leitura e sempre reconstruivel.
 */
@Component
@Order(10) // depois dos bootstrappers de dados (Order 3 e seguintes)
public class ReadModelRebuilder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ReadModelRebuilder.class);
    private final AirportService airportService;
    private final RouteService routeService;

    public ReadModelRebuilder(AirportService airportService, RouteService routeService) {
        this.airportService = airportService;
        this.routeService = routeService;
    }

    @Override
    public void run(String... args) {
        int airports = airportService.rebuildReadModel();
        int routes = routeService.rebuildReadModel();
        log.info("Modelo de leitura reconstruido: {} aeroportos, {} rotas", airports, routes);
    }
}
