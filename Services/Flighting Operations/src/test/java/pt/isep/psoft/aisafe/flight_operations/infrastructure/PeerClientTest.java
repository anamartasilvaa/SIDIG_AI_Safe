package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.domain.SchedulingRuleViolation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PeerClientTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private static final ParameterizedTypeReference<List<String>> LIST = new ParameterizedTypeReference<>() {};

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    /** Fake RemoteCaller: one behavior per peer. */
    static class FakeCaller extends RemoteCaller {
        final Map<String, Object> behaviour = new ConcurrentHashMap<>();

        FakeCaller() {
            super(null, 1, Duration.ZERO, 3, Duration.ofSeconds(10));
        }

        FakeCaller on(String peer, Object result) {
            behaviour.put(peer, result);
            return this;
        }

        private Object answer(String url) {
            for (Map.Entry<String, Object> e : behaviour.entrySet()) {
                if (url.startsWith(e.getKey())) {
                    Object b = e.getValue();
                    if (b instanceof Duration delay) {
                        try { Thread.sleep(delay.toMillis()); } catch (InterruptedException ignored) { }
                        return "slow@" + e.getKey();
                    }
                    if (b instanceof RuntimeException ex) throw ex;
                    if (b instanceof Optional<?> o && o.isEmpty()) return null;   // 404
                    return b;
                }
            }
            return null;
        }

        @Override @SuppressWarnings("unchecked")
        public <T> Optional<T> get(String url, Class<T> type) {
            return Optional.ofNullable((T) answer(url));
        }

        @Override @SuppressWarnings("unchecked")
        public <T> Optional<T> get(String url, ParameterizedTypeReference<T> type) {
            Object r = answer(url);
            return Optional.of((T) (r == null ? List.of() : List.of(r)));
        }

        @Override @SuppressWarnings("unchecked")
        public <T> Optional<T> put(String url, Object body, Class<T> type) {
            return Optional.ofNullable((T) answer(url));
        }
    }

    private PeerClient client(FakeCaller caller, String... peers) {
        return new PeerClient(caller, List.of(peers), executor);
    }

    @Test
    @DisplayName("Aggregation asks peers IN PARALLEL (time ~ slowest peer, not the sum)")
    void aggregationIsParallel() {
        FakeCaller caller = new FakeCaller()
                .on("http://p1", Duration.ofMillis(600))
                .on("http://p2", Duration.ofMillis(600));
        long start = System.nanoTime();
        AggregatedResult<String> result = client(caller, "http://p1", "http://p2").collectAll("/x", LIST);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, result.items().size());
        assertFalse(result.isPartial());
        assertTrue(elapsedMs < 1100, "took " + elapsedMs + " ms, should be ~600 ms");
    }

    @Test
    @DisplayName("Peer with 401 or down is ignored in forwarding")
    void failingPeersAreSkipped() {
        FakeCaller caller = new FakeCaller()
                .on("http://bad", new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
                .on("http://down", new RemoteUnavailableException("http://down", "circuit open"))
                .on("http://good", "flight");
        Optional<String> found = client(caller, "http://bad", "http://down", "http://good").findFirst("/f/1", String.class);
        assertEquals(Optional.of("flight"), found);
    }
    @Test
    @DisplayName("Owner replica down: 503 (might exist), not 404 (does not exist)")
    void ownerDownIsNotNotFound() {
        FakeCaller caller = new FakeCaller()
                .on("http://empty", Optional.empty())
                .on("http://down", new RemoteUnavailableException("http://down", "timeout"));
        PeerClient client = client(caller, "http://empty", "http://down");

        assertThrows(DependencyUnavailableException.class, () -> client.findFirst("/f/9", String.class));
        assertThrows(DependencyUnavailableException.class, () -> client.forwardPut("/f/9/status", "x", String.class));
    }

    @Test
    @DisplayName("Aggregation with failing peers returns PARTIAL response and states who failed")
    void partialAggregation() {
        FakeCaller caller = new FakeCaller()
                .on("http://bad", new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
                .on("http://down", new RemoteUnavailableException("http://down", "timeout"))
                .on("http://good", "flight");
        AggregatedResult<String> result = client(caller, "http://bad", "http://down", "http://good").collectAll("/y", LIST);

        assertTrue(result.isPartial());
        assertEquals(List.of("flight"), result.items());
        assertTrue(result.unreachablePeers().containsAll(List.of("http://bad", "http://down")));
    }

    @Test
    @DisplayName("findFirst returns the fastest peer without waiting for the slow one")
    void findFirstReturnsFastest() {
        FakeCaller caller = new FakeCaller()
                .on("http://slow", Duration.ofMillis(1500))
                .on("http://fast", "fast");
        long start = System.nanoTime();
        Optional<String> found = client(caller, "http://slow", "http://fast").findFirst("/f/2", String.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Optional.of("fast"), found);
        assertTrue(elapsedMs < 1000, "took " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("404 on all peers -> empty")
    void notFoundAnywhere() {
        FakeCaller caller = new FakeCaller().on("http://a", Optional.empty()).on("http://b", Optional.empty());
        assertTrue(client(caller, "http://a", "http://b").findFirst("/f/3", String.class).isEmpty());
    }

    @Test
    @DisplayName("409 from owner replica is propagated to client (invalid transition)")
    void conflictFromOwnerIsPropagated() {
        FakeCaller caller = new FakeCaller()
                .on("http://other", Optional.empty())
                .on("http://owner", new HttpClientErrorException(HttpStatus.CONFLICT));
        assertThrows(SchedulingRuleViolation.class, () ->
                client(caller, "http://other", "http://owner").forwardPut("/f/4/status", Map.of("status", "CANCELED"), String.class));
    }

    @Test
    @DisplayName("No peers configured -> empty, no error")
    void noPeers() {
        assertTrue(client(new FakeCaller(), "").findFirst("/z", String.class).isEmpty());
    }
}