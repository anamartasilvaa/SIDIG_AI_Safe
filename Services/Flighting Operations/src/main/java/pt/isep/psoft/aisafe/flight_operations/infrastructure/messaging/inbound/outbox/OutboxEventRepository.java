package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findBySentAtIsNullOrderByIdAsc(Limit limit);

    long countBySentAtIsNull();

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.sentAt IS NOT NULL AND e.sentAt < :before")
    int deleteSentBefore(Instant before);
}