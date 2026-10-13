package pt.isep.psoft.aisafe.flight_operations.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

import java.util.List;

@Repository
public interface ScheduledFlightRepository extends JpaRepository<ScheduledFlight, String> {

    List<ScheduledFlight> findByAircraftRegistration(String aircraftRegistration);

    // US229: Rotas mais frequentes (apenas dados LOCAIS; a agregacao e feita no service)
    @Query("SELECT f.routeId, COUNT(f) FROM ScheduledFlight f GROUP BY f.routeId ORDER BY COUNT(f) DESC")
    List<Object[]> countFlightsGroupedByRoute();
}
