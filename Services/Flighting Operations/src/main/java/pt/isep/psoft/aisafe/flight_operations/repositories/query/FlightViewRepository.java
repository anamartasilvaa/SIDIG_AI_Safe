package pt.isep.psoft.aisafe.flight_operations.repositories.query;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.psoft.aisafe.flight_operations.application.query.FlightView;

import java.util.List;

public interface FlightViewRepository extends JpaRepository<FlightView, String> {

    List<FlightView> findByRegistrationNumberOrderByScheduledDateTimeAsc(String registrationNumber);

    long countByRouteIdAndStatusNot(String routeId, String status);
}