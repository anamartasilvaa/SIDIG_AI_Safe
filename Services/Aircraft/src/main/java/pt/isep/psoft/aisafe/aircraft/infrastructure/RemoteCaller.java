package pt.isep.psoft.aisafe.aircraft.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.time.Duration;
import java.util.Optional;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RemoteCaller {
    private final RestTemplate rest; private final int attempts; private final Duration backoff;
    private final int threshold; private final Duration openDuration;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    public RemoteCaller(RestTemplate rest, @Value("${app.http.max-attempts}") int attempts,
                        @Value("${app.http.initial-backoff}") Duration backoff,
                        @Value("${app.http.circuit-breaker.failure-threshold}") int threshold,
                        @Value("${app.http.circuit-breaker.open-duration}") Duration openDuration) {
        this.rest=rest; this.attempts=Math.max(1, attempts); this.backoff=backoff;
        this.threshold=threshold; this.openDuration=openDuration;
    }
    public <T> Optional<T> get(String url, Class<T> type) { return get(url, ParameterizedTypeReference.forType(type)); }
    public <T> Optional<T> get(String url, ParameterizedTypeReference<T> type) {
        CircuitBreaker breaker = breaker(url);
        if (!breaker.tryAcquire()) throw new RemoteUnavailableException(url, "circuit open");
        long delay=backoff.toMillis();
        for (int n=1;;n++) try {
            Optional<T> result = Optional.ofNullable(rest.exchange(url, HttpMethod.GET, null, type).getBody());
            breaker.onSuccess(); return result;
        } catch (HttpClientErrorException.NotFound e) { breaker.onSuccess(); return Optional.empty();
        } catch (HttpClientErrorException e) { breaker.onSuccess(); throw e;
        } catch (RestClientException e) {
            if (n >= attempts) { breaker.onFailure(); throw new RemoteUnavailableException(url,e); }
            sleep(delay);
            delay*=2;
        }
    }
    public <T> Optional<T> post(String url, Object body, Class<T> type) {
        CircuitBreaker breaker = breaker(url);
        if (!breaker.tryAcquire()) throw new RemoteUnavailableException(url, "circuit open");
        long delay = backoff.toMillis();
        for (int n = 1; ; n++) {
            try {
                Optional<T> result = Optional.ofNullable(rest.postForObject(url, body, type));
                breaker.onSuccess(); return result;
            } catch (RestClientException e) {
                if (n >= attempts) {
                    breaker.onFailure();
                    throw new RemoteUnavailableException(url, e);
                }
                sleep(delay);
                delay *= 2;
            }
        }
    }

    public <T> Optional<T> put(String url, Object body, Class<T> type) {
            CircuitBreaker breaker = breaker(url);
            if (!breaker.tryAcquire()) throw new RemoteUnavailableException(url, "circuit open");
            long delay = backoff.toMillis();
            for (int n = 1; ; n++) {
                try {
                    Optional<T> result = Optional.ofNullable(rest.exchange(
                            url, HttpMethod.PUT, new HttpEntity<>(body), type).getBody());
                    breaker.onSuccess();
                    return result;
                } catch (RestClientException e) {
                    if (n >= attempts) {
                        breaker.onFailure();
                        throw new RemoteUnavailableException(url, e);
                    }
                    sleep(delay);
                    delay *= 2;
            }
        }
    }

    public CircuitBreaker.State breakerState(String url) { return breaker(url).state(); }

    private CircuitBreaker breaker(String url) {
        return breakers.computeIfAbsent(target(url), ignored -> new CircuitBreaker(threshold, openDuration));
    }

    private static String target(String url) {
        try { URI u = URI.create(url); return u.getHost() + ":" + u.getPort(); }
        catch (IllegalArgumentException e) { return url; }
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RemoteUnavailableException("interrupted while calling remote service", interrupted);
        }
    }
}
