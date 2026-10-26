package pt.isep.psoft.aisafe.maintenance.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import pt.isep.psoft.aisafe.maintenance.domain.MaintenancePart;
import pt.isep.psoft.aisafe.maintenance.domain.PartNumber;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaintenancePartRepository extends JpaRepository<MaintenancePart, Long> {
    Optional<MaintenancePart> findByPartNumber(PartNumber partNumber);
    Optional<MaintenancePart> findByPartNumberNumber(String partNumber);

    @Query("SELECT p FROM MaintenancePart p WHERE p.stockQuantity <= p.minimumThreshold")
    List<MaintenancePart> findPartsWithLowStock();
}
