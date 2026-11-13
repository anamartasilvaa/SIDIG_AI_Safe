package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class IdempotencyGuard {

    private final ProcessedEventRepository repository;
    private final Clock clock;

    public IdempotencyGuard(ProcessedEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }


    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumer, String eventId) {
        ProcessedEvent.Key key = new ProcessedEvent.Key(consumer, eventId);
        if (repository.existsById(key)) {
            return false;
        }
        repository.saveAndFlush(new ProcessedEvent(consumer, eventId, clock.instant()));
        return true;
    }
}