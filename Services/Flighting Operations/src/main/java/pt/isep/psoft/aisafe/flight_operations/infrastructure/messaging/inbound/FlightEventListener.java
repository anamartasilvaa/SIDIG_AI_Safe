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
import pt.isep.psoft.aisafe.flight_operations.application.FlightReplicaService;
import pt.isep.psoft.aisafe.flight_operations.application.events.FlightState;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.EventTypes;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.IntegrationEvent;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Consumes the flight events of ALL Flight Operations replicas (this one included) from this
 * replica's own queue fo.flights.&lt;instance&gt;. Replaces the HTTP replication of P1.
 * Ack only after the commit; malformed -> dead-letter queue; temporary failure -> requeue
 * (max 5 deliveries, then dead-letter queue).
 */
@Component
public class FlightEventListener {

    static final String CONSUMER = "fo.flights";
    private static final Logger log = LoggerFactory.getLogger(FlightEventListener.class);
    private static final Set<String> FLIGHT_TYPES = Set.of(
            EventTypes.FLIGHT_SCHEDULED, EventTypes.FLIGHT_STATUS_CHANGED, EventTypes.FLIGHT_REASSIGNED);

    private final FlightReplicaService replicas;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public FlightEventListener(FlightReplicaService replicas, IdempotencyGuard idempotency,
                               TransactionTemplate tx, ObjectMapper eventMapper) {
        this.replicas = replicas;
        this.idempotency = idempotency;
        this.tx = tx;
        this.mapper = eventMapper;
    }

    @RabbitListener(queues = "#{foFlightsQueue.name}")
    public void onMessage(Message message) {
        IntegrationEvent event;
        FlightState state;
        try {
            event = mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), IntegrationEvent.class);
            if (!FLIGHT_TYPES.contains(event.type())) {
                return; // not a flight state event
            }
            state = toFlightState(event, mapper);
        } catch (Exception e) {
            log.error("Flight event {} rejected (malformed): {}",
                    message.getMessageProperties().getMessageId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Malformed flight event", e);
        }
        tx.executeWithoutResult(status -> {
            if (!idempotency.firstTime(CONSUMER, event.eventId())) {
                log.debug("Flight event {} already processed.", event.eventId());
                return;
            }
            replicas.apply(state, event.correlationId());
        });
    }

    /**
     * FlightScheduled carries the FlightState as payload; FlightStatusChanged and FlightReassigned
     * carry {"flight": FlightState, ...}.
     */
    static FlightState toFlightState(IntegrationEvent event, ObjectMapper mapper) throws Exception {
        if (event.eventId() == null || event.payload() == null) {
            throw new IllegalArgumentException("Flight event without eventId or payload.");
        }
        JsonNode node = event.payload().has("flight") ? event.payload().get("flight") : event.payload();
        FlightState state = mapper.treeToValue(node, FlightState.class);
        if (state.flightId() == null || state.routeId() == null || state.registrationNumber() == null
                || state.scheduledDateTime() == null || state.status() == null || state.lastModifiedAt() == null) {
            throw new IllegalArgumentException("Incomplete flight state in event " + event.eventId());
        }
        return state;
    }
}
