package pt.isep.psoft.aisafe.flight_operations.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledFlightRepository extends JpaRepository<ScheduledFlight, Long> {

    Optional<ScheduledFlight> findByFlightId_Uuid(String flightId);

    List<ScheduledFlight> findByAircraft_Number(String registrationNumber);

    // US229: rotas mais frequentes (contagem local; a soma entre replicas e feita no service)
    @Query("SELECT f.routeId.code, COUNT(f) FROM ScheduledFlight f GROUP BY f.routeId.code ORDER BY COUNT(f) DESC")
    List<Object[]> countFlightsGroupedByRoute();
}
