package pt.isep.psoft.aisafe.flight_operations.messaging.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final Duration confirmTimeout;
    private final Duration maxBackoff;
    private final Duration retention;

    private int consecutiveFailures = 0;
    private Instant nextAttempt = Instant.EPOCH;

    public OutboxRelay(OutboxEventRepository repository, RabbitTemplate rabbit, TransactionTemplate tx, Clock clock,
                       @Value("${app.outbox.batch-size:100}") int batchSize,
                       @Value("${app.outbox.confirm-timeout:5s}") Duration confirmTimeout,
                       @Value("${app.outbox.max-backoff:30s}") Duration maxBackoff,
                       @Value("${app.outbox.retention:7d}") Duration retention) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = batchSize;
        this.confirmTimeout = confirmTimeout;
        this.maxBackoff = maxBackoff;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:500ms}")
    public void publishPending() {
        if (clock.instant().isBefore(nextAttempt)) {
            return;
        }
        List<OutboxEvent> pending = repository.findBySentAtIsNullOrderByIdAsc(Limit.of(batchSize));
        for (OutboxEvent event : pending) {
            String error = publish(event);
            if (error == null) {
                tx.executeWithoutResult(s -> repository.findById(event.getId())
                        .ifPresent(e -> e.markSent(clock.instant())));
                consecutiveFailures = 0;
            } else {
                tx.executeWithoutResult(s -> repository.findById(event.getId())
                        .ifPresent(e -> e.markFailed(error)));
                scheduleBackoff(event, error);
                return;
            }
        }
    }

    /** @return null if the broker confirmed the event, otherwise the reason. */
    private String publish(OutboxEvent event) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(event.getEventId());
        props.setType(event.getEventType());
        props.setCorrelationId(event.getCorrelationId());
        props.setHeader("aggregateId", event.getAggregateId());
        Message message = new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), props);

        CorrelationData correlation = new CorrelationData(event.getEventId());
        try {
            rabbit.send(event.getExchangeName(), event.getRoutingKey(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) {
                return "Unroutable (no queue bound): " + correlation.getReturned().getReplyText();
            }
            if (!confirm.ack()) {
                return "Broker nack: " + confirm.reason();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for the confirm";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void scheduleBackoff(OutboxEvent event, String error) {
        consecutiveFailures++;
        long millis = Math.min(maxBackoff.toMillis(), 500L * (1L << Math.min(consecutiveFailures, 10)));
        nextAttempt = clock.instant().plusMillis(millis);
        log.warn("Outbox: event {} ({}) not published: {}. Retrying in {} ms ({} pending).",
                event.getEventId(), event.getEventType(), error, millis, repository.countBySentAtIsNull());
    }

    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval:1h}")
    public void cleanup() {
        Instant before = clock.instant().minus(retention);
        Integer removed = tx.execute(s -> repository.deleteSentBefore(before));
        if (removed != null && removed > 0) {
            log.info("Outbox: {} published events older than {} removed.", removed, retention);
        }
    }

    public long pendingCount() {
        return repository.countBySentAtIsNull();
    }
}