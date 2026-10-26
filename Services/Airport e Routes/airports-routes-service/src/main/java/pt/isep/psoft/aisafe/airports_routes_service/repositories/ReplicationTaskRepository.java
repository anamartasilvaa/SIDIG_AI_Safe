package pt.isep.psoft.aisafe.airports_routes_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;
import java.time.Instant;
import java.util.List;

public interface ReplicationTaskRepository extends JpaRepository<ReplicationTask, Long> {
    List<ReplicationTask> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
            ReplicationTask.Status status, Instant now);
}
