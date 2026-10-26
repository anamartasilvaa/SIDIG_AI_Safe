package pt.isep.psoft.aisafe.aircraft.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pt.isep.psoft.aisafe.aircraft.application.DTO.TopAircraftModelDTO;
import pt.isep.psoft.aisafe.aircraft.domain.Aircraft;
import pt.isep.psoft.aisafe.aircraft.domain.AircraftStatus;
import pt.isep.psoft.aisafe.aircraft.domain.ModelName;
import pt.isep.psoft.aisafe.aircraft.domain.RegistrationNumber;

import java.util.List;
import java.util.Optional;

public interface AircraftRepository extends JpaRepository<Aircraft, Long> {
    Optional<Aircraft> findByRegistrationNumber(RegistrationNumber registrationNumber);

    List<Aircraft> findByStatus(AircraftStatus status);
    List<Aircraft> findByModel_ModelName(ModelName modelName);

    @Query("SELECT a FROM Aircraft a WHERE YEAR(a.manufacturingDate) = :year")
    List<Aircraft> findByManufacturingYear(@Param("year") int year);

    @Query("SELECT new pt.isep.psoft.aisafe.aircraft.application.DTO.TopAircraftModelDTO(a.model.modelName.name, SUM(a.totalFlightHours)) " +
            "FROM Aircraft a GROUP BY a.model.modelName.name ORDER BY SUM(a.totalFlightHours) DESC")
    List<TopAircraftModelDTO> findTop5ModelsByFlightHours(org.springframework.data.domain.Pageable pageable);

    // US204 - Top 5 Models by Assignments
    @Query("SELECT new pt.isep.psoft.aisafe.aircraft.application.DTO.TopAircraftModelDTO(a.model.modelName.name, SUM(a.totalAssignments)) " +
            "FROM Aircraft a GROUP BY a.model.modelName.name ORDER BY SUM(a.totalAssignments) DESC")
    List<TopAircraftModelDTO> findTop5ModelsByAssignments(org.springframework.data.domain.Pageable pageable);

    // US223 - Top 5 Individual Aircraft by Assignments
    @Query("SELECT new pt.isep.psoft.aisafe.aircraft.application.DTO.TopAircraftModelDTO(a.registrationNumber.number, a.totalAssignments) " +
            "FROM Aircraft a ORDER BY a.totalAssignments DESC")
    List<TopAircraftModelDTO> findTop5AircraftByAssignments(org.springframework.data.domain.Pageable pageable);

    // --- US223: Agrupa por Avião (Matrícula) por Horas de Voo ---
    @Query("SELECT new pt.isep.psoft.aisafe.aircraft.application.DTO.TopAircraftModelDTO(a.registrationNumber.number, a.totalFlightHours) " +
            "FROM Aircraft a ORDER BY a.totalFlightHours DESC")
    List<TopAircraftModelDTO> findTop5AircraftByFlightHours(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT a FROM Aircraft a WHERE " +
            "(:engineType IS NULL OR a.model.engineType = :engineType) AND " +
            "(:feature IS NULL OR a.features LIKE %:feature%)")
    List<Aircraft> findByFeaturesAndEngine(@Param("feature") String feature,
                                           @Param("engineType") String engineType);
}
