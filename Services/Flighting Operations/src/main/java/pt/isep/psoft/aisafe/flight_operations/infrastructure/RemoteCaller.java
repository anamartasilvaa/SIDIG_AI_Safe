package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * PASSO 3 - GET remoto com retry e backoff exponencial.
 *  - 404 -> Optional.empty() (o recurso nao existe la; nao vale a pena repetir)
 *  - outros 4xx -> propaga (erro do pedido, repetir nao resolve)
 *  - timeout / ligacao recusada / 5xx -> repete; esgotadas as tentativas lanca RemoteUnavailableException
 */
@Component
public class RemoteCaller {

    private static final Logger log = LoggerFactory.getLogger(RemoteCaller.class);

    private final RestTemplate rest;
    private final int maxAttempts;
    private final Duration initialBackoff;

    public RemoteCaller(RestTemplate rest,
                        @Value("${app.http.max-attempts}") int maxAttempts,
                        @Value("${app.http.initial-backoff}") Duration initialBackoff) {
        this.rest = rest;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff;
    }

    public <T> Optional<T> get(String url, Class<T> type) {
        return get(url, ParameterizedTypeReference.forType(type));
    }

    public <T> Optional<T> get(String url, ParameterizedTypeReference<T> type) {
        long backoff = initialBackoff.toMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                return Optional.ofNullable(rest.exchange(url, HttpMethod.GET, null, type).getBody());
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            } catch (HttpClientErrorException e) {
                throw e;
            } catch (RestClientException e) {
                if (attempt >= maxAttempts) {
                    log.warn("GET {} falhou apos {} tentativa(s): {}", url, attempt, e.getMessage());
                    throw new RemoteUnavailableException(url, e);
                }
                log.debug("GET {} falhou (tentativa {}), nova tentativa em {} ms", url, attempt, backoff);
                sleep(backoff);
                backoff *= 2;
            }
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
