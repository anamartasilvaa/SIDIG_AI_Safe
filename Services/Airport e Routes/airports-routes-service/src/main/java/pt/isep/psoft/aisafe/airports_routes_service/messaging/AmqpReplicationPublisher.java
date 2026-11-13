package pt.isep.psoft.aisafe.airports_routes_service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ReplicationTaskRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Lado "command" da replicacao: le as tarefas PENDING da tabela outbox e publica-as como
 * eventos no exchange fanout. So marca a tarefa como DONE depois de o broker confirmar a
 * receção (publisher confirm), o que da entrega "pelo menos uma vez".
 */
@Component
@ConditionalOnProperty(name = "app.replication.transport", havingValue = "amqp")
public class AmqpReplicationPublisher {

    private static final Logger log = LoggerFactory.getLogger(AmqpReplicationPublisher.class);

    private final ReplicationTaskRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper mapper;
    private final String exchange;

    public AmqpReplicationPublisher(ReplicationTaskRepository repository,
                                    RabbitTemplate rabbitTemplate,
                                    ObjectMapper mapper,
                                    @Value("${app.messaging.exchange:airports.events}") String exchange) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.mapper = mapper;
        this.exchange = exchange;
    }

    @Scheduled(fixedDelay = 2000)
    public void publishPendingTasks() {
        for (ReplicationTask task :
                repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
                        ReplicationTask.Status.PENDING, Instant.now())) {
            try {
                Message message = buildMessage(task);
                rabbitTemplate.invoke(operations -> {
                    operations.send(exchange, "", message);
                    operations.waitForConfirmsOrDie(5000);
                    return null;
                });
                task.markDone();
                log.info("Evento {} (tarefa {}) publicado em {}",
                        message.getMessageProperties().getType(), task.getId(), exchange);
            } catch (Exception ex) {
                task.fail(Instant.now().plusSeconds(backoff(task.getAttempts())));
                log.error("Falha ao publicar a tarefa {} no broker (tentativa {})",
                        task.getId(), task.getAttempts(), ex);
            }
            if (task.getStatus() == ReplicationTask.Status.FAILED) {
                log.error("Tarefa {} falhou definitivamente apos {} tentativas",
                        task.getId(), task.getAttempts());
            }
            repository.save(task);
        }
    }

    private Message buildMessage(ReplicationTask task) {
        ReplicationEventType type = ReplicationEventType.from(task.getHttpMethod(), task.getPath());

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("taskId", String.valueOf(task.getId()));
        envelope.put("eventType", type.name());
        envelope.put("method", task.getHttpMethod());
        envelope.put("path", task.getPath());
        envelope.put("payload", task.getPayload());
        envelope.put("originalUser", task.getOriginalUser());
        envelope.put("lastModified", task.getLastModified() == null ? null : task.getLastModified().toString());
        envelope.put("originNodeId", task.getOriginNodeId());

        byte[] body;
        try {
            body = mapper.writeValueAsBytes(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException("Nao foi possivel serializar o evento", ex);
        }

        return MessageBuilder.withBody(body)
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(task.getOriginNodeId() + "-" + task.getId())
                .setType(type.name())
                .setHeader("originNodeId", task.getOriginNodeId())
                .build();
    }

    private long backoff(int attempts) {
        return Math.min(60L, 5L << attempts);
    }
}
