package pt.isep.psoft.aisafe.maintenance.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.maintenance.domain.ProcessedEvent;
import pt.isep.psoft.aisafe.maintenance.repositories.ProcessedEventRepository;

@Component
public class IdempotencyGuard {
    private final ProcessedEventRepository repository;

    public IdempotencyGuard(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean firstTime(String consumer, String eventId) {
        if (eventId == null || eventId.isBlank()) return false;
        if (repository.findByConsumerAndEventId(consumer, eventId).isPresent()) return false;
        repository.save(new ProcessedEvent(consumer, eventId));
        return true;
    }
}
