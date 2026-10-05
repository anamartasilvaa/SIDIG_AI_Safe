package pt.isep.psoft.aisafe.flight_operations.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightStatus;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledFlightRepository extends JpaRepository<ScheduledFlight, Long> {

    // Substituímos o objeto Aircraft completo pela String da matrícula
    boolean existsByAircraftRegistrationAndStatus(String aircraftRegistration, FlightStatus status);

    // Substituímos o RegistrationNumber pela String direta
    List<ScheduledFlight> findByAircraftRegistration(String aircraftRegistration);

    // US229: Rotas mais frequentes (ajustado para usar apenas a String routeId)
    @Query("SELECT f.routeId, COUNT(f) " +
            "FROM ScheduledFlight f " +
            "GROUP BY f.routeId " +
            "ORDER BY COUNT(f) DESC")
    List<Object[]> countFlightsGroupedByRoute();

    // US223: Utilização por avião e por mês (ajustado para usar apenas a String aircraftRegistration)
    @Query("SELECT f.aircraftRegistration, FUNCTION('DATE_FORMAT', f.scheduledDateTime, '%Y-%m'), COUNT(f) " +
            "FROM ScheduledFlight f " +
            "WHERE f.scheduledDateTime BETWEEN :start AND :end " +
            "GROUP BY f.aircraftRegistration, FUNCTION('DATE_FORMAT', f.scheduledDateTime, '%Y-%m')")
    List<Object[]> countFlightsByAircraftAndMonth(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // NOTA: As queries de eficiência de combustível foram removidas.
    // Terão de ser feitas via código (pedidos HTTP) mais tarde.
}