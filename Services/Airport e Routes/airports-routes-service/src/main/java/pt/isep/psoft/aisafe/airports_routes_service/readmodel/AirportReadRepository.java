package pt.isep.psoft.aisafe.airports_routes_service.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repositorio do modelo de leitura (separado do AirportRepository, que e do modelo de escrita). */
@Repository
public interface AirportReadRepository extends JpaRepository<AirportReadModel, String> {

    List<AirportReadModel> findByNameContainingIgnoreCase(String name);

    List<AirportReadModel> findByCityIgnoreCase(String city);

    List<AirportReadModel> findByCountryIgnoreCase(String country);
}
