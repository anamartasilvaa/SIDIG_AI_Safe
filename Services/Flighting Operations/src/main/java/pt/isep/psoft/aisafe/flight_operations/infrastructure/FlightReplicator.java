package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class FlightReplicator {

    private static final Logger log = LoggerFactory.getLogger(FlightReplicator.class);
    static final String REPLICA_PATH = "/internal/scheduled-flights/%s/replica";

    private final RemoteCaller caller;
    private final List<String> peers;
    private final boolean enabled;
    private final Duration retryInterval;
    private final Map<String, Map<String, ScheduledFlightViewDTO>> pending = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public FlightReplicator(RemoteCaller caller,
                            @Value("${app.peers}") List<String> peers,
                            @Value("${app.replication.enabled:true}") boolean enabled,
                            @Value("${app.replication.retry-interval:5s}") Duration retryInterval) {
        this.caller = caller;
        this.peers = peers.stream().map(String::trim).filter(p -> !p.isBlank()).toList();
        this.enabled = enabled;
        this.retryInterval = retryInterval;
        this.peers.forEach(p -> {
            pending.put(p, new ConcurrentHashMap<>());
            locks.put(p, new ReentrantLock());
        });

        AtomicInteger counter = new AtomicInteger();
        this.scheduler = Executors.newScheduledThreadPool(Math.max(1, this.peers.size()), runnable -> {
            Thread t = new Thread(runnable, "replication-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        if (enabled && !this.peers.isEmpty()) {
            long ms = retryInterval.toMillis();
            scheduler.scheduleWithFixedDelay(this::retryAll, ms, ms, TimeUnit.MILLISECONDS);
        }
        log.info("Replicacao {} (peers: {}, retry a cada {})", enabled ? "ATIVA" : "DESLIGADA", this.peers, retryInterval);
    }

    public boolean isEnabled() {
        return enabled;
    }


    public void replicate(ScheduledFlightViewDTO flight) {
        if (!enabled) {
            return;
        }
        for (String peer : peers) {
            pending.get(peer).merge(flight.id(), flight, ScheduledFlightViewDTO::newest);
            scheduler.execute(() -> flush(peer));
        }
    }


    public int pendingFor(String peer) {
        Map<String, ScheduledFlightViewDTO> queue = pending.get(peer);
        return queue == null ? 0 : queue.size();
    }

    void retryAll() {
        for (String peer : peers) {
            try {
                if (pendingFor(peer) > 0) {
                    flush(peer);
                }
            } catch (RuntimeException e) {
                log.warn("Erro inesperado na replicacao para {}: {}", peer, e.toString());
            }
        }
    }

    void flush(String peer) {
        ReentrantLock lock = locks.get(peer);
        if (!lock.tryLock()) {
            return;
        }
        try {
            Map<String, ScheduledFlightViewDTO> queue = pending.get(peer);
            while (!queue.isEmpty()) {
                for (ScheduledFlightViewDTO flight : List.copyOf(queue.values())) {
                    try {
                        caller.put(peer + REPLICA_PATH.formatted(flight.id()), flight, Void.class);
                        queue.remove(flight.id(), flight);
                        log.info("REPLICA voo {} (rev {}, {}) -> {}", flight.id(), flight.revision(), flight.status(), peer);
                    } catch (RemoteUnavailableException e) {
                        log.warn("REPLICA -> {} indisponivel: {} voo(s) pendente(s), nova tentativa em {}",
                                peer, queue.size(), retryInterval);
                        return;
                    } catch (HttpClientErrorException e) {
                        if (e.getStatusCode().value() == 409) {
                            log.warn("REPLICA voo {} -> {} em conflito (409), nova tentativa em {}",
                                    flight.id(), peer, retryInterval);
                            return;
                        }
                        queue.remove(flight.id(), flight);
                        log.warn("REPLICA voo {} -> {} recusada ({}), descartada", flight.id(), peer,
                                e.getStatusCode().value());
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
