package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/**
 * Keeps the idempotency table (processed_event) bounded. A message is redelivered within seconds or
 * minutes (at most 5 deliveries), so the ids of events processed more than the retention period ago
 * (7 days by default, like the outbox) are no longer needed.
 */
@Component
public class ProcessedEventCleanup {

    static final String SQL = "DELETE FROM fo_command.processed_event WHERE processed_at < ?";
    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleanup.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration retention;

    public ProcessedEventCleanup(JdbcTemplate jdbc, Clock clock,
                                 @Value("${app.idempotency.retention:7d}") Duration retention) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval:1h}",
               initialDelayString = "${app.idempotency.cleanup-interval:1h}")
    public int cleanup() {
        int removed = jdbc.update(SQL, Timestamp.from(clock.instant().minus(retention)));
        if (removed > 0) {
            log.info("Idempotency table: {} old entries removed.", removed);
        }
        return removed;
    }
}
