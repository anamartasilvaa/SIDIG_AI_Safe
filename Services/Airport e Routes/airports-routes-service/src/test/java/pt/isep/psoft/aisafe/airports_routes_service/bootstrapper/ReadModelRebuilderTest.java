package pt.isep.psoft.aisafe.airports_routes_service.bootstrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;

import static org.mockito.Mockito.*;

/** CQRS: o modelo de leitura e estado derivado; ao arrancar e reconstruido a partir do modelo de escrita. */
@ExtendWith(MockitoExtension.class)
class ReadModelRebuilderTest {

    @Mock AirportService airportService;
    @Mock RouteService routeService;

    @Test
    void startupRebuildsAirportsThenRoutes() {
        when(airportService.rebuildReadModel()).thenReturn(5);
        when(routeService.rebuildReadModel()).thenReturn(11);

        new ReadModelRebuilder(airportService, routeService).run();

        InOrder order = inOrder(airportService, routeService);
        order.verify(airportService).rebuildReadModel();
        order.verify(routeService).rebuildReadModel();
    }
}
