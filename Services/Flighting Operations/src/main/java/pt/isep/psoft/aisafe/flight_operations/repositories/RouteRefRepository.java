package pt.isep.psoft.aisafe.flight_operations.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.RouteRef;

public interface RouteRefRepository extends JpaRepository<RouteRef, String> {
}