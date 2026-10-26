package pt.isep.psoft.aisafe.maintenance.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RemoteCaller {
    private static final Logger log = LoggerFactory.getLogger(RemoteCaller.class);

    private final RestTemplate rest;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final int breakerThreshold;
    private final Duration breakerOpenDuration;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public RemoteCaller(RestTemplate rest,
                        @Value("${app.http.max-attempts}") int maxAttempts,
                        @Value("${app.http.initial-backoff}") Duration initialBackoff,
                        @Value("${app.http.circuit-breaker.failure-threshold}") int breakerThreshold,
                        @Value("${app.http.circuit-breaker.open-duration}") Duration breakerOpenDuration) {
        this.rest = rest;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff;
        this.breakerThreshold = breakerThreshold;
        this.breakerOpenDuration = breakerOpenDuration;
    }

    public <T> Optional<T> get(String url, Class<T> type) {
        return call(url, HttpMethod.GET, null, ParameterizedTypeReference.forType(type));
    }

    public <T> Optional<T> get(String url, ParameterizedTypeReference<T> type) {
        return call(url, HttpMethod.GET, null, type);
    }

    public <T> Optional<T> put(String url, Object body, Class<T> type) {
        return call(url, HttpMethod.PUT, body, ParameterizedTypeReference.forType(type));
    }

    public <T> Optional<T> patch(String url, Object body, Class<T> type) {
        return call(url, HttpMethod.PATCH, body, ParameterizedTypeReference.forType(type));
    }

    public CircuitBreaker.State breakerState(String url) {
        return breakerFor(url).state();
    }

    private <T> Optional<T> call(String url, HttpMethod method, Object body, ParameterizedTypeReference<T> type) {
        CircuitBreaker breaker = breakerFor(url);
        if (!breaker.tryAcquire()) {
            log.debug("{} {} blocked: circuit open", method, url);
            throw new RemoteUnavailableException(url, "circuit open");
        }

        HttpEntity<Object> entity = body == null ? null : new HttpEntity<>(body);
        long backoff = initialBackoff.toMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                T result = rest.exchange(url, method, entity, type).getBody();
                breaker.onSuccess();
                return Optional.ofNullable(result);
            } catch (HttpClientErrorException.NotFound e) {
                breaker.onSuccess();
                return Optional.empty();
            } catch (HttpClientErrorException e) {
                breaker.onSuccess();
                throw e;
            } catch (RestClientException e) {
                if (attempt >= maxAttempts) {
                    breaker.onFailure();
                    log.warn("{} {} failed after {} attempt(s): {}", method, url, attempt, e.getMessage());
                    throw new RemoteUnavailableException(url, e);
                }
                log.debug("{} {} failed (attempt {}), retrying in {} ms", method, url, attempt, backoff);
                sleep(backoff);
                backoff *= 2;
            }
        }
    }

    private CircuitBreaker breakerFor(String url) {
        String target = targetOf(url);
        return breakers.computeIfAbsent(target, t -> new CircuitBreaker(t, breakerThreshold, breakerOpenDuration));
    }

    private static String targetOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() + ":" + uri.getPort();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
