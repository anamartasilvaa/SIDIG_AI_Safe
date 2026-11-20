package pt.isep.psoft.aisafe.airports_routes_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ProcessedEvent;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByConsumerAndEventId(String consumer, String eventId);
    long deleteByProcessedAtBefore(Instant limit);
}
