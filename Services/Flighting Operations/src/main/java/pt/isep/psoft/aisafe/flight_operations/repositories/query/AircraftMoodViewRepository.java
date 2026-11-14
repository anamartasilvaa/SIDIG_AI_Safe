package pt.isep.psoft.aisafe.flight_operations.repositories.query;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.psoft.aisafe.flight_operations.application.query.AircraftMood;
import pt.isep.psoft.aisafe.flight_operations.application.query.AircraftMoodView;

import java.util.List;

public interface AircraftMoodViewRepository extends JpaRepository<AircraftMoodView, String> {

    List<AircraftMoodView> findAllByOrderByRegistrationNumberAsc();

    List<AircraftMoodView> findByMoodOrderByRegistrationNumberAsc(AircraftMood mood);
}
