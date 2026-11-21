package pt.isep.psoft.aisafe.maintenance.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.psoft.aisafe.maintenance.readmodel.AircraftReference;

import java.util.Optional;

public interface AircraftReferenceRepository extends JpaRepository<AircraftReference, String> {
    Optional<AircraftReference> findByRegistrationNumber(String registrationNumber);
}
