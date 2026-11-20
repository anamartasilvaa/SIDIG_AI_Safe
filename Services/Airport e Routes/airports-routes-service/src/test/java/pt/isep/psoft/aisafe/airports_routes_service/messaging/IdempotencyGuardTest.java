package pt.isep.psoft.aisafe.airports_routes_service.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ProcessedEvent;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ProcessedEventRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Inbox / consumidor idempotente: cada evento e aplicado no maximo uma vez. */
@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    @Mock ProcessedEventRepository repository;

    private IdempotencyGuard guard;
    private final AtomicInteger applied = new AtomicInteger();

    @BeforeEach
    void setUp() {
        guard = new IdempotencyGuard(repository, Duration.ofDays(7));
    }

    @Test
    void newEventIsAppliedAndRecorded() throws Exception {
        assertTrue(guard.runOnce("c", "e-1", "ROUTE_CREATED", applied::incrementAndGet));

        assertEquals(1, applied.get());
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(repository).save(captor.capture());
        assertEquals("c", captor.getValue().getConsumer());
        assertEquals("e-1", captor.getValue().getEventId());
        assertNotNull(captor.getValue().getProcessedAt());
    }

    @Test
    void alreadyProcessedEventIsSkipped() throws Exception {
        when(repository.existsByConsumerAndEventId("c", "e-1")).thenReturn(true);

        assertFalse(guard.runOnce("c", "e-1", "ROUTE_CREATED", applied::incrementAndGet));

        assertEquals(0, applied.get());
        verify(repository, never()).save(any());
    }

    @Test
    void failureInTheActionIsPropagatedAndNothingIsRecorded() {
        assertThrows(IllegalStateException.class, () ->
                guard.runOnce("c", "e-1", "ROUTE_CREATED", () -> { throw new IllegalStateException("boom"); }));

        verify(repository, never()).save(any());
    }

    @Test
    void eventWithoutIdIsAppliedButNotRecorded() throws Exception {
        assertTrue(guard.runOnce("c", null, "ROUTE_CREATED", applied::incrementAndGet));

        assertEquals(1, applied.get());
        verifyNoInteractions(repository);
    }

    @Test
    void cleanupRemovesRecordsOlderThanTheRetention() {
        guard.cleanup();

        ArgumentCaptor<Instant> limit = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByProcessedAtBefore(limit.capture());
        long days = Duration.between(limit.getValue(), Instant.now()).toDays();
        assertEquals(7, days);
    }
}
