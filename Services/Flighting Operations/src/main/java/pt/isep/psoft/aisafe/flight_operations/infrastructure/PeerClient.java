package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.domain.SchedulingRuleViolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

@Component
public class PeerClient {

    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);

    private final RemoteCaller caller;
    private final List<String> peers;
    private final ExecutorService executor;

    public PeerClient(RemoteCaller caller,
                      @Value("${app.peers}") List<String> peers,
                      @Qualifier("peerExecutor") ExecutorService executor) {
        this.caller = caller;
        this.peers = peers.stream().map(String::trim).filter(p -> !p.isBlank()).toList();
        this.executor = executor;
        log.info("Peers configurados: {}", this.peers);
    }

    public <T> Optional<T> findFirst(String internalPath, Class<T> type) {
        return firstPresent("GET " + internalPath, peer -> caller.get(peer + internalPath, type));
    }

    public <T> Optional<T> forwardPut(String internalPath, Object body, Class<T> type) {
        return firstPresent("PUT " + internalPath, peer -> {
            try {
                return caller.put(peer + internalPath, body, type);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                    throw new SchedulingRuleViolation("Rejected by owning replica " + peer
                            + ": invalid status transition.");
                }
                throw e;
            }
        });
    }
    public <T> AggregatedResult<T> collectAll(String internalPath, ParameterizedTypeReference<List<T>> type) {
        Map<String, Future<List<T>>> futures = new java.util.LinkedHashMap<>();
        for (String peer : peers) {
            log.info("AGGREGATE GET {}{}", peer, internalPath);
            futures.put(peer, executor.submit(() -> caller.get(peer + internalPath, type).orElse(List.of())));
        }

        List<T> items = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        futures.forEach((peer, future) -> {
            try {
                items.addAll(future.get());
            } catch (ExecutionException e) {
                log.warn("Peer {} falhou ({}), resposta sera parcial", peer, describe(e.getCause()));
                unreachable.add(peer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unreachable.add(peer);
            }
        });
        return new AggregatedResult<>(items, unreachable);
    }

    private <T> Optional<T> firstPresent(String operation, PeerCall<T> call) {
        if (peers.isEmpty()) {
            return Optional.empty();
        }
        CompletionService<Optional<T>> completion = new ExecutorCompletionService<>(executor);
        Map<Future<Optional<T>>, String> owners = new ConcurrentHashMap<>();
        for (String peer : peers) {
            log.info("FORWARD {} -> {}", operation, peer);
            owners.put(completion.submit(() -> call.apply(peer)), peer);
        }

        try {
            for (int i = 0; i < peers.size(); i++) {
                Future<Optional<T>> done = completion.take();
                String peer = owners.get(done);
                try {
                    Optional<T> result = done.get();
                    if (result.isPresent()) {
                        log.info("FOUND em {}", peer);
                        return result;
                    }
                    log.info("NOT FOUND em {}", peer);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof SchedulingRuleViolation violation) {
                        throw violation;
                    }
                    log.warn("Peer {} falhou ({}), a ignorar", peer, describe(e.getCause()));
                }
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            owners.keySet().forEach(f -> f.cancel(true));
        }
    }

    private static String describe(Throwable t) {
        if (t instanceof HttpClientErrorException http) {
            return "respondeu " + http.getStatusCode().value();
        }
        if (t instanceof RemoteUnavailableException || t instanceof RestClientException) {
            return t.getMessage();
        }
        return t == null ? "erro desconhecido" : t.toString();
    }

    @FunctionalInterface
    private interface PeerCall<T> {
        Optional<T> apply(String peer) throws Exception;
    }
}
