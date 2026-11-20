package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RouteViewDTO;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ReplicationTaskRepository;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Transactional outbox: Entrega 2 (AMQP) grava UMA tarefa por evento, que o exchange fanout entrega a
 * todas as instancias; Entrega 1 (HTTP) gravava uma tarefa por par.
 */
@ExtendWith(MockitoExtension.class)
class ReplicationOutboxTest {

    private static final List<String> PEERS = List.of("https://localhost:8092", "https://localhost:8093");
    private static final Instant WHEN = Instant.parse("2026-11-15T10:00:00Z");

    @Mock ReplicationTaskRepository repository;

    private ReplicationOutbox outbox;

    @BeforeEach
    void setUp() {
        outbox = new ReplicationOutbox(repository, new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private List<ReplicationTask> saved(int times) {
        ArgumentCaptor<ReplicationTask> captor = ArgumentCaptor.forClass(ReplicationTask.class);
        verify(repository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void amqpModeSavesOneTaskPerEventRegardlessOfThePeers() {
        ReflectionTestUtils.setField(outbox, "transport", "amqp");

        outbox.enqueue(PEERS, "PATCH", "/api/routes/RT-1/deactivate", null, WHEN, "airports-routes-1");

        ReplicationTask task = saved(1).get(0);
        assertEquals("amqp://broker", task.getPeerUrl());
        assertEquals("PATCH", task.getHttpMethod());
        assertEquals("/api/routes/RT-1/deactivate", task.getPath());
        assertEquals(ReplicationTask.Status.PENDING, task.getStatus());
        assertEquals(WHEN, task.getLastModified());
        assertEquals("airports-routes-1", task.getOriginNodeId());
    }

    @Test
    void httpModeSavesOneTaskPerPeer() {
        ReflectionTestUtils.setField(outbox, "transport", "http");

        outbox.enqueue(PEERS, "PATCH", "/api/routes/RT-1/deactivate", null, WHEN, "airports-routes-1");

        List<ReplicationTask> tasks = saved(2);
        assertEquals(PEERS, tasks.stream().map(ReplicationTask::getPeerUrl).toList());
    }

    @Test
    void isAmqpReflectsTheConfiguredTransport() {
        ReflectionTestUtils.setField(outbox, "transport", "AMQP");
        assertTrue(outbox.isAmqp());
        ReflectionTestUtils.setField(outbox, "transport", "http");
        assertFalse(outbox.isAmqp());
    }

    @Test
    void metadataIsTakenFromTheViewPayload() {
        ReflectionTestUtils.setField(outbox, "transport", "amqp");
        RouteViewDTO view = new RouteViewDTO("RT-1", "LIS", "OPO", "ACTIVE", 50, WHEN, "airports-routes-2", 300.0, 50);

        outbox.enqueue(PEERS, "POST", "/api/routes", view);

        ReplicationTask task = saved(1).get(0);
        assertEquals(WHEN, task.getLastModified());
        assertEquals("airports-routes-2", task.getOriginNodeId());
        assertTrue(task.getPayload().contains("\"routeId\":\"RT-1\""));
    }

    @Test
    void originalUserComesFromTheTokenAndIsSanitised() {
        ReflectionTestUtils.setField(outbox, "transport", "amqp");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("evil\nuser", null, List.of()));

        outbox.enqueue(PEERS, "PATCH", "/api/airports/OPO/status", "CLOSED", WHEN, "airports-routes-1");

        assertEquals("evil_user", saved(1).get(0).getOriginalUser());
    }

    @Test
    void withoutAuthenticationTheUserIsSystem() {
        ReflectionTestUtils.setField(outbox, "transport", "amqp");

        outbox.enqueue(PEERS, "PATCH", "/api/airports/OPO/status", "CLOSED", WHEN, "airports-routes-1");

        assertEquals("SYSTEM", saved(1).get(0).getOriginalUser());
    }

    @Test
    void newTaskIsReadyToBePublishedImmediately() {
        ReflectionTestUtils.setField(outbox, "transport", "amqp");
        Instant before = Instant.now();

        outbox.enqueue(PEERS, "PATCH", "/api/routes/RT-1/deactivate", null, WHEN, "airports-routes-1");

        ReplicationTask task = saved(1).get(0);
        assertEquals(0, task.getAttempts());
        assertFalse(task.getNextAttemptAt().isBefore(before));
        assertFalse(task.getNextAttemptAt().isAfter(Instant.now()));
        verify(repository, never()).delete(any());
    }
}
