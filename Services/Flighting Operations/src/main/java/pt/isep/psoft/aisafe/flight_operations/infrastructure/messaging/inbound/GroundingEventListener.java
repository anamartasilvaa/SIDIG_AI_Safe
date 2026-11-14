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
import pt.isep.psoft.aisafe.flight_operations.application.AircraftGroundingHandler;
import pt.isep.psoft.aisafe.flight_operations.application.reference.AircraftChange;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Consumes the shared work queue fo.grounding: aircraft.status-changed (Aircraft Service) and
 * fo.grounding.requested (Flight Operations). Only ONE replica processes each message; the
 * reassignments reach the other replica as FlightReassigned / FlightStatusChanged events.
 * Ack only after the commit; malformed -> dead-letter queue.
 */
@Component
public class GroundingEventListener {

    static final String CONSUMER = "fo.grounding";
    private static final Logger log = LoggerFactory.getLogger(GroundingEventListener.class);

    private final AircraftEventTranslator translator;
    private final AircraftGroundingHandler handler;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Clock clock;

    public GroundingEventListener(AircraftEventTranslator translator, AircraftGroundingHandler handler,
                                  IdempotencyGuard idempotency, TransactionTemplate tx,
                                  ObjectMapper eventMapper, Clock clock) {
        this.translator = translator;
        this.handler = handler;
        this.idempotency = idempotency;
        this.tx = tx;
        this.mapper = eventMapper;
        this.clock = clock;
    }

    @RabbitListener(queues = "#{foGroundingQueue.name}")
    public void onMessage(Message message) {
        String eventId;
        String correlationId;
        boolean groundingRequest;
        AircraftChange change;
        try {
            JsonNode envelope = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            eventId = translator.eventId(envelope, message.getMessageProperties().getMessageId());
            correlationId = envelope.hasNonNull("correlationId") ? envelope.get("correlationId").asText() : eventId;
            groundingRequest = translator.isGroundingRequest(envelope);
            change = translator.translate(envelope, clock.instant());
        } catch (Exception e) {
            log.error("Grounding message {} rejected (malformed): {}",
                    message.getMessageProperties().getMessageId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Malformed grounding message", e);
        }
        tx.executeWithoutResult(status -> {
            if (!idempotency.firstTime(CONSUMER, eventId)) {
                log.debug("Grounding message {} already processed.", eventId);
                return;
            }
            if (groundingRequest) {
                handler.onGroundingRequested(change.registrationNumber(), correlationId);
            } else {
                handler.onAircraftStatus(change, correlationId);
            }
        });
    }
}
