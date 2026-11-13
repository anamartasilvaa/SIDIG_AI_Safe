package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {
}