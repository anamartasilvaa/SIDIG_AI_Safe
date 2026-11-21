package pt.isep.psoft.aisafe.maintenance.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.psoft.aisafe.maintenance.domain.ProcessedEvent;

import java.util.Optional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    Optional<ProcessedEvent> findByConsumerAndEventId(String consumer, String eventId);
}
