package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class FlightReplicatorTest {

    private FlightReplicator replicator;

    static class FakePeers extends RemoteCaller {
        final Map<String, Boolean> down = new ConcurrentHashMap<>();
        final List<String> received = new CopyOnWriteArrayList<>();

        FakePeers() {
            super(null, 1, Duration.ZERO, 3, Duration.ofSeconds(10));
        }

        @Override
        public <T> Optional<T> put(String url, Object body, Class<T> type) {
            String peer = url.substring(0, url.indexOf("/internal"));
            if (down.getOrDefault(peer, false)) {
                throw new RemoteUnavailableException(url, "down");
            }
            ScheduledFlightViewDTO f = (ScheduledFlightViewDTO) body;
            received.add(peer + "|" + f.id() + "|" + f.revision());
            return Optional.empty();
        }
    }

    private static ScheduledFlightViewDTO flight(String id, long revision) {
        return new ScheduledFlightViewDTO(id, "OPO-LIS", "CS-TTA", "2027-03-15T10:00", "SCHEDULED",
                null, null, revision, "2026-10-20T10:00:00Z");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @AfterEach
    void stop() {
        if (replicator != null) replicator.shutdown();
    }

    @Test
    @DisplayName("Um voo novo e enviado em segundo plano a todos os peers")
    void replicatesToAllPeers() throws InterruptedException {
        FakePeers peers = new FakePeers();
        replicator = new FlightReplicator(peers, List.of("http://p2", "http://p3"), true, Duration.ofSeconds(5));

        replicator.replicate(flight("a", 1));
        waitUntil(() -> peers.received.size() == 2 && replicator.pendingFor("http://p2") == 0);

        assertTrue(peers.received.containsAll(List.of("http://p2|a|1", "http://p3|a|1")));
        assertEquals(0, replicator.pendingFor("http://p2"));
    }

    @Test
    @DisplayName("Peer em baixo: a copia fica pendente e e entregue quando ele volta (convergencia)")
    void pendingUntilPeerComesBack() throws InterruptedException {
        FakePeers peers = new FakePeers();
        peers.down.put("http://p2", true);
        replicator = new FlightReplicator(peers, List.of("http://p2"), true, Duration.ofMillis(100));

        replicator.replicate(flight("a", 1));
        replicator.replicate(flight("a", 2));          // so a copia mais recente fica pendente
        waitUntil(() -> replicator.pendingFor("http://p2") == 1);
        assertEquals(1, replicator.pendingFor("http://p2"));
        assertTrue(peers.received.isEmpty());

        peers.down.put("http://p2", false);            // o peer volta
        waitUntil(() -> replicator.pendingFor("http://p2") == 0);

        assertEquals(List.of("http://p2|a|2"), peers.received);
    }

    @Test
    @DisplayName("Com a replicacao desligada nada e enviado")
    void disabled() throws InterruptedException {
        FakePeers peers = new FakePeers();
        replicator = new FlightReplicator(peers, List.of("http://p2"), false, Duration.ofMillis(100));

        replicator.replicate(flight("a", 1));
        Thread.sleep(200);

        assertTrue(peers.received.isEmpty());
        assertEquals(0, replicator.pendingFor("http://p2"));
    }
}
