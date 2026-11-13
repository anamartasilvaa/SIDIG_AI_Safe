package pt.isep.psoft.aisafe.airports_routes_service.readmodel;

import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.airports_routes_service.domain.Airport;
import pt.isep.psoft.aisafe.airports_routes_service.domain.Route;

/**
 * CQRS, PROJETOR das rotas: transforma o agregado Route (modelo de escrita) numa linha do modelo
 * de leitura. Chamado na mesma transacao de cada comando (criar, atualizar, desativar), local ou
 * resultante de um evento recebido do broker.
 */
@Component
public class RouteProjector {

    private final RouteReadRepository repository;

    public RouteProjector(RouteReadRepository repository) {
        this.repository = repository;
    }

    public void project(Route route) {
        Airport origin = route.getOrigin();
        Airport destination = route.getDestination();
        repository.save(new RouteReadModel(
                route.getRouteId().id(),
                origin.getIataCode().code(),
                destination.getIataCode().code(),
                route.getStatus().name(),
                route.getMinimumCapacity(),
                route.getMinimumRange(),
                route.getEstimatedFlightTime(),
                route.getLastModified(),
                route.getOriginNodeId(),
                origin.getCoordinates().latitude(),
                origin.getCoordinates().longitude(),
                destination.getCoordinates().latitude(),
                destination.getCoordinates().longitude()));
    }
}
