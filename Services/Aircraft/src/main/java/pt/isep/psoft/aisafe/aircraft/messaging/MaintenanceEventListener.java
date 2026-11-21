package pt.isep.psoft.aisafe.aircraft.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.psoft.aisafe.aircraft.application.AircraftService;

import java.nio.charset.StandardCharsets;

@Component
public class MaintenanceEventListener {
    private final AircraftService aircrafts;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper;

    public MaintenanceEventListener(AircraftService aircrafts, IdempotencyGuard idempotency,
                                    TransactionTemplate transactionTemplate, ObjectMapper eventMapper) {
        this.aircrafts = aircrafts;
        this.idempotency = idempotency;
        this.transactionTemplate = transactionTemplate;
        this.mapper = eventMapper;
    }

    @RabbitListener(queues = "#{aircraftMaintenanceQueue.name}")
    public void onMessage(Message message) {
        try {
            JsonNode event = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            JsonNode payload = event.path("payload");
            if (payload.isMissingNode()) payload = event;
            String eventId = event.path("eventId").asText(
                    message.getMessageProperties().getMessageId());
            String registration = payload.path("registrationNumber").asText(payload.path("registration").asText());
            String type = event.path("type").asText(event.path("eventType").asText());
            transactionTemplate.executeWithoutResult(status -> {
                if (!idempotency.firstTime("aircraft.maintenance", eventId)) return;
                if ("MaintenanceStarted".equals(type) || "MaintenanceRegistered".equals(type)) {
                    aircrafts.applyMaintenanceStatus(registration, "UNDER_MAINTENANCE");
                } else if ("MaintenanceCompleted".equals(type)) {
                    aircrafts.applyMaintenanceStatus(registration, "ACTIVE");
                }
            });
        } catch (Exception ex) {
            throw new AmqpRejectAndDontRequeueException("Malformed maintenance event", ex);
        }
    }
}
