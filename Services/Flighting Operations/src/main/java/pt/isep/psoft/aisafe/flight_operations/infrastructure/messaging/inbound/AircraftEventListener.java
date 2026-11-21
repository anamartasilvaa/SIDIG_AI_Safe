package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.psoft.aisafe.flight_operations.application.reference.AircraftChange;
import pt.isep.psoft.aisafe.flight_operations.application.reference.AircraftReferenceService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Consumes the Aircraft Service events from this replica's own queue fo.aircraft.<instance>
 * and keeps aircraft_ref up to date.
 * Ack only after the commit; malformed -> dead-letter queue; temporary failure -> requeue
 * (max 5 deliveries, then dead-letter queue).
 */

@Component
public class AircraftEventListener {

    static final String CONSUMER = "fo.aircraft";
    private static final Logger log = LoggerFactory.getLogger(AircraftEventListener.class);

    private final AircraftEventTranslator translator;
    private final AircraftReferenceService references;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AircraftEventListener(AircraftEventTranslator translator, AircraftReferenceService references,
                                 IdempotencyGuard idempotency, TransactionTemplate tx,
                                 ObjectMapper eventMapper, Clock clock) {
        this.translator = translator;
        this.references = references;
        this.idempotency = idempotency;
        this.tx = tx;
        this.mapper = eventMapper;
        this.clock = clock;
    }

    @RabbitListener(queues = "#{foAircraftQueue.name}")
    public void onMessage(Message message) {
        String eventId;
        AircraftChange change;
        try {
            JsonNode envelope = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            eventId = translator.eventId(envelope, message.getMessageProperties().getMessageId());
            change = translator.translate(envelope, clock.instant());
        } catch (Exception e) {
            log.error("Aircraft event {} rejected (malformed): {}",
                    message.getMessageProperties().getMessageId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Malformed aircraft event", e);
        }
        tx.executeWithoutResult(status -> {
            if (!idempotency.firstTime(CONSUMER, eventId)) {
                log.debug("Aircraft event {} already processed.", eventId);
                return;
            }
            references.apply(change);
        });
    }
}
