package pt.isep.psoft.aisafe.aircraft.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.psoft.aisafe.aircraft.domain.ProcessedEvent;

import java.util.Optional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    Optional<ProcessedEvent> findByConsumerAndEventId(String consumer, String eventId);
}
