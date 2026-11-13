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
import pt.isep.psoft.aisafe.flight_operations.application.reference.ReferenceDataService;
import pt.isep.psoft.aisafe.flight_operations.application.reference.RouteChange;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;

@Component
public class RouteEventListener {

    static final String CONSUMER = "fo.routes";
    private static final Logger log = LoggerFactory.getLogger(RouteEventListener.class);

    private final AirportsRoutesTranslator translator;
    private final ReferenceDataService referenceData;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RouteEventListener(AirportsRoutesTranslator translator, ReferenceDataService referenceData,
                              IdempotencyGuard idempotency, TransactionTemplate tx,
                              ObjectMapper eventMapper, Clock clock) {
        this.translator = translator;
        this.referenceData = referenceData;
        this.idempotency = idempotency;
        this.tx = tx;
        this.mapper = eventMapper;
        this.clock = clock;
    }

    @RabbitListener(queues = "#{foRoutesQueue.name}")
    public void onMessage(Message message) {
        Optional<RouteChange> change;
        try {
            JsonNode envelope = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            change = translator.translate(envelope, clock.instant());
        } catch (Exception e) {
            log.error("Route event {} rejected (malformed): {}", messageId(message), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Malformed route event", e);
        }
        if (change.isEmpty()) {
            return;
        }
        String eventId = messageId(message);
        tx.executeWithoutResult(status -> {
            if (!idempotency.firstTime(CONSUMER, eventId)) {
                log.debug("Route event {} already processed.", eventId);
                return;
            }
            referenceData.applyRouteChange(change.get());
        });
    }

    private static String messageId(Message message) {
        String id = message.getMessageProperties().getMessageId();
        if (id == null || id.isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Route event without messageId");
        }
        return id;
    }
}