package pt.isep.psoft.aisafe.airports_routes_service.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ProcessedEvent;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ProcessedEventRepository;

import java.time.Duration;
import java.time.Instant;

/**
 * Consumidor idempotente (Inbox): aplica um evento no maximo uma vez por consumidor.
 * O efeito do evento e o registo em processed_event sao gravados na mesma transacao:
 * se o efeito falhar, nada fica registado e o evento pode ser tentado de novo.
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    /** Acao que aplica o evento (pode lancar excecoes verificadas, ex.: JSON invalido). */
    @FunctionalInterface
    public interface EventAction {
        void apply() throws Exception;
    }

    private final ProcessedEventRepository repository;
    private final Duration retention;

    public IdempotencyGuard(ProcessedEventRepository repository,
                            @Value("${app.messaging.processed-event.retention:7d}") Duration retention) {
        this.repository = repository;
        this.retention = retention;
    }

    /**
     * @return true se o evento foi aplicado agora; false se ja tinha sido processado (duplicado).
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean runOnce(String consumer, String eventId, String eventType, EventAction action) throws Exception {
        if (eventId == null || eventId.isBlank()) {
            action.apply(); // sem id nao e possivel deduplicar: continua idempotente por last-writer-wins
            return true;
        }
        if (repository.existsByConsumerAndEventId(consumer, eventId)) {
            log.info("Evento duplicado ignorado: {} ({})", eventId, eventType);
            return false;
        }
        action.apply();
        repository.save(new ProcessedEvent(consumer, eventId, eventType));
        return true;
    }

    /** Limpeza periodica: o TTL das filas e de 1 h, por isso um duplicado nunca chega depois de dias. */
    @Scheduled(fixedDelayString = "${app.messaging.processed-event.cleanup-interval:1h}")
    @Transactional
    public void cleanup() {
        long removed = repository.deleteByProcessedAtBefore(Instant.now().minus(retention));
        if (removed > 0) {
            log.info("Removidos {} registos antigos de processed_event", removed);
        }
    }
}
