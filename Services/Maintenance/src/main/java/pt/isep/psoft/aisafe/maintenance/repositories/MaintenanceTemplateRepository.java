package pt.isep.psoft.aisafe.maintenance.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pt.isep.psoft.aisafe.maintenance.domain.MaintenanceTemplate;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaintenanceTemplateRepository extends JpaRepository<MaintenanceTemplate, Long> {
    Optional<MaintenanceTemplate> findByTemplateName(String templateName);
    Optional<MaintenanceTemplate> findByGlobalId(String globalId);
    List<MaintenanceTemplate> findByAppliesToModelNamesContaining(String modelName);
}
