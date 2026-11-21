package pt.isep.psoft.aisafe.maintenance.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.psoft.aisafe.maintenance.readmodel.AircraftReference;
import pt.isep.psoft.aisafe.maintenance.repositories.AircraftReferenceRepository;

import java.nio.charset.StandardCharsets;

@Component
public class AircraftEventListener {
    private final AircraftReferenceRepository references;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper;

    public AircraftEventListener(AircraftReferenceRepository references, IdempotencyGuard idempotency,
                                 TransactionTemplate transactionTemplate, ObjectMapper eventMapper) {
        this.references = references;
        this.idempotency = idempotency;
        this.transactionTemplate = transactionTemplate;
        this.mapper = eventMapper;
    }

    @RabbitListener(queues = "#{maintenanceAircraftQueue.name}")
    public void onMessage(Message message) {
        try {
            JsonNode event = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            String eventId = event.path("eventId").asText(message.getMessageProperties().getMessageId());
            JsonNode payload = event.path("payload");
            if (payload.isMissingNode()) payload = event;
            String registration = payload.path("registrationNumber").asText(payload.path("registration").asText());
            String model = payload.path("modelName").asText(payload.path("model").asText("UNKNOWN"));
            String status = payload.path("status").asText("UNKNOWN");
            if (registration.isBlank()) return;
            transactionTemplate.executeWithoutResult(tx -> {
                if (!idempotency.firstTime("maintenance.aircraft", eventId)) return;
                references.findById(registration).ifPresentOrElse(
                        reference -> reference.update(model, status),
                        () -> references.save(new AircraftReference(registration, model, status)));
            });
        } catch (Exception ex) {
            throw new AmqpRejectAndDontRequeueException("Malformed aircraft event", ex);
        }
    }
}
