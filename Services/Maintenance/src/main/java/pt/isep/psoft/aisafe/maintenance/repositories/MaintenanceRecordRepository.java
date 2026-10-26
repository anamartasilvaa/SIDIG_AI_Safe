package pt.isep.psoft.aisafe.maintenance.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pt.isep.psoft.aisafe.maintenance.domain.ComponentCategory;
import pt.isep.psoft.aisafe.maintenance.domain.MaintenanceRecord;
import pt.isep.psoft.aisafe.maintenance.domain.MaintenanceRecordStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {
    List<MaintenanceRecord> findByRegistrationNumberOrderByStartDateDesc(String registrationNumber);
    List<MaintenanceRecord> findByStatus(MaintenanceRecordStatus status);
    Optional<MaintenanceRecord> findByGlobalId(String globalId);
    Optional<MaintenanceRecord> findTopByRegistrationNumberAndStatusOrderByCompletionDateDesc(String registrationNumber, MaintenanceRecordStatus status);

    @Query("SELECT m FROM MaintenanceRecord m WHERE " +
            "(:registrationNumber IS NULL OR m.registrationNumber = :registrationNumber) AND " +
            "(:startDate IS NULL OR m.startDate >= :startDate) AND " +
            "(:endDate IS NULL OR m.startDate <= :endDate) AND " +
            "(:category IS NULL OR m.componentCategory = :category)")
    Page<MaintenanceRecord> searchRecords(@Param("registrationNumber") String registrationNumber,
                                         @Param("startDate") LocalDateTime startDate,
                                         @Param("endDate") LocalDateTime endDate,
                                         @Param("category") ComponentCategory category,
                                         Pageable pageable);
}
