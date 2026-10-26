package pt.isep.psoft.aisafe.maintenance.infrastructure;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.maintenance.application.DTO.MaintenanceRecordViewDTO;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.client.HttpClientErrorException;

@Component
public class MaintenanceReplicator {
    private final RemoteCaller caller; private final List<String> peers; private final boolean enabled;
    private final Map<String, Map<String, MaintenanceRecordViewDTO>> pending = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Duration retryInterval;
    private final ScheduledExecutorService scheduler;
    public MaintenanceReplicator(RemoteCaller caller, @Value("${app.peers}") List<String> configured,
                                 @Value("${app.replication.enabled:true}") boolean enabled,
                                 @Value("${app.replication.retry-interval:5s}") Duration interval) {
        this.caller=caller; this.enabled=enabled; this.retryInterval=interval;
        peers=configured.stream().map(String::trim).filter(s -> !s.isBlank()).toList();
        peers.forEach(p -> {
            pending.put(p, new ConcurrentHashMap<>());
            locks.put(p, new ReentrantLock());
        });
        AtomicInteger counter = new AtomicInteger();
        scheduler = Executors.newScheduledThreadPool(Math.max(1, peers.size()), runnable -> {
            Thread thread = new Thread(runnable, "maintenance-replication-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        if (enabled && !peers.isEmpty()) {
            scheduler.scheduleWithFixedDelay(this::flushAll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
    public void replicate(MaintenanceRecordViewDTO record) { if (!enabled) return;
        peers.forEach(p -> { pending.get(p).merge(record.globalId(),record,(a,b)->a.revision()>=b.revision()?a:b); scheduler.execute(() -> flush(p)); }); }
    private void flushAll() { peers.forEach(this::flush); }
    private void flush(String peer) {
        ReentrantLock lock = locks.get(peer);
        if (lock == null || !lock.tryLock()) return;
        try {
            Map<String, MaintenanceRecordViewDTO> queue = pending.get(peer);
            while (!queue.isEmpty()) {
                for (MaintenanceRecordViewDTO record : List.copyOf(queue.values())) {
                    try {
                        caller.put(peer + "/internal/maintenance/records/" + record.globalId() + "/replica",
                                record, Void.class);
                        queue.remove(record.globalId(), record);
                    } catch (RemoteUnavailableException e) {
                        return;
                    } catch (HttpClientErrorException e) {
                        if (e.getStatusCode().value() == 409) return;
                        queue.remove(record.globalId(), record);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }
    public boolean isEnabled() { return enabled; }
    @PreDestroy void shutdown() { scheduler.shutdownNow(); }
}
