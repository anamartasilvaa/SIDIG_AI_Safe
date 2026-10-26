package pt.isep.psoft.aisafe.aircraft.infrastructure;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.aircraft.application.DTO.AircraftViewDTO;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class AircraftReplicator {
    private static final String REPLICA_PATH = "/internal/aircraft/instances/%s/replica";

    private final RemoteCaller caller;
    private final List<String> peers;
    @Getter
    private final boolean enabled;
    private final Duration retryInterval;
    private final Map<String, Map<String, AircraftViewDTO>> pending = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public AircraftReplicator(RemoteCaller caller, @Value("${app.peers}") List<String> peers,
                              @Value("${app.replication.enabled:true}") boolean enabled,
                              @Value("${app.replication.retry-interval:5s}") Duration retryInterval) {
        this.caller = caller;
        this.peers = peers.stream().map(String::trim).filter(p -> !p.isBlank()).toList();
        this.enabled = enabled;
        this.retryInterval = retryInterval;
        peers.forEach(p -> {
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

    public void replicate(AircraftViewDTO value) {
        if (!enabled) return;
        peers.forEach(p -> {
            pending.get(p).merge(value.registrationNumber(), value, (a, b) -> a.revision() >= b.revision() ? a : b);
            scheduler.execute(() -> flush(p));
        });
    }

    private void retryAll() {
        peers.forEach(p -> {
            try {
                flush(p);
            } catch (RuntimeException e) {
                log.warn("Erro ao replicar para {}: {}", p, e.getMessage());
            }
        });
    }

    private void flush(String peer) {
        ReentrantLock lock = locks.get(peer);
        if (!lock.tryLock()) return;
        try {
            for (AircraftViewDTO v : List.copyOf(pending.get(peer).values()))
                try {
                    caller.put(peer + REPLICA_PATH.formatted(v.registrationNumber()), v, Void.class);
                    pending.get(peer).remove(v.registrationNumber(), v);
                } catch (RemoteUnavailableException e) {
                    return;
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
