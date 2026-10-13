package pt.isep.psoft.aisafe.airports_routes_service.application;

import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AlternativeRouteDTO;
import java.util.List;

public interface RouteSearchStrategy {

    /**
     * Procura rotas alternativas entre dois aeroportos.
     * @param originIata Código IATA do aeroporto de partida
     * @param destinationIata Código IATA do aeroporto de chegada
     * @return Lista de viagens alternativas possíveis
     */
    List<AlternativeRouteDTO> findAlternatives(String originIata, String destinationIata);

}