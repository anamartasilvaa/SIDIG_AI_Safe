package pt.isep.psoft.aisafe.airports_routes_service.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repositorio do modelo de leitura das rotas (separado do RouteRepository, que e do modelo de escrita). */
@Repository
public interface RouteReadRepository extends JpaRepository<RouteReadModel, String> {

    List<RouteReadModel> findByOriginIataAndDestinationIata(String originIata, String destinationIata);

    List<RouteReadModel> findByOriginIata(String originIata);

    List<RouteReadModel> findByDestinationIata(String destinationIata);

    List<RouteReadModel> findByOriginIataOrDestinationIata(String originIata, String destinationIata);

    long countByOriginIataOrDestinationIata(String originIata, String destinationIata);

    List<RouteReadModel> findByStatusOrderByMinimumRangeAsc(String status);

    List<RouteReadModel> findByOriginIataAndStatus(String originIata, String status);

    @Query("SELECT SUM(r.minimumRange) FROM RouteReadModel r WHERE r.status = 'ACTIVE'")
    Double calculateTotalNetworkDistance();
}
