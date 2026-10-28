package pt.isep.psoft.aisafe.airports_routes_service.config;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Prevents a slow or unavailable peer from consuming request threads indefinitely.
 * The persistent outbox remains responsible for eventual delivery of writes.
 */
public final class PeerResilienceInterceptor implements ClientHttpRequestInterceptor {
    private final ConcurrentMap<String, Circuit> circuits = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final long openDurationMillis;

    public PeerResilienceInterceptor(int failureThreshold, Duration openDuration,
                                     int maxRetries, Duration retryBackoff) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMillis = Math.max(1, openDuration.toMillis());
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String peer = peerKey(request.getURI());
        Circuit circuit = circuits.computeIfAbsent(peer, ignored -> new Circuit());
        if (!circuit.tryAcquire()) {
            throw new ResourceAccessException("Peer circuit is open: " + peer);
        }

        try {
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getStatusCode().is5xxServerError()) {
                circuit.failure();
                response.close();
                throw new ResourceAccessException("Peer returned "
                        + response.getStatusCode().value() + ": " + peer);
            } else {
                circuit.success();
            }
            return response;
        } catch (IOException | RuntimeException exception) {
            circuit.failure();
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private String peerKey(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private final class Circuit {
        private int failures;
        private long openedAt;

        synchronized boolean tryAcquire() {
            if (openedAt == 0) {
                return true;
            }
            if (System.currentTimeMillis() - openedAt >= openDurationMillis) {
                openedAt = 0;
                failures = 0;
                return true;
            }
            return false;
        }

        synchronized void success() {
            failures = 0;
            openedAt = 0;
        }

        synchronized void failure() {
            failures++;
            if (failures >= failureThreshold) {
                openedAt = System.currentTimeMillis();
            }
        }
    }
}
