package pt.isep.psoft.aisafe.flight_operations.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;

import java.util.List;
import java.util.Optional;

public interface AircraftRefRepository extends JpaRepository<AircraftRef, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AircraftRef a WHERE a.registrationNumber = :registration")
    Optional<AircraftRef> findForUpdate(@Param("registration") String registration);
    List<AircraftRef> findByStatusOrderByRegistrationNumberAsc(String status);
}