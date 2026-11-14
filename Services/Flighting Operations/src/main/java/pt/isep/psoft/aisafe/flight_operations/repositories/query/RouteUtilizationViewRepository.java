package pt.isep.psoft.aisafe.flight_operations.repositories.query;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.psoft.aisafe.flight_operations.application.query.RouteUtilizationView;

import java.util.List;

public interface RouteUtilizationViewRepository extends JpaRepository<RouteUtilizationView, String> {

    List<RouteUtilizationView> findByFlightCountGreaterThanOrderByFlightCountDescRouteIdAsc(long minimum);
}