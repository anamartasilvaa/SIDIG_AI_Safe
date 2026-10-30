package pt.isep.psoft.aisafe.maintenance.infrastructure;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import pt.isep.psoft.aisafe.maintenance.application.DTO.MaintenanceRecordViewDTO;
import pt.isep.psoft.aisafe.maintenance.application.DTO.MaintenanceTemplateViewDTO;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class MaintenanceReplicator {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceReplicator.class);
    private final RemoteCaller caller;
    private final List<String> peers;
    private final boolean enabled;
    private final Duration retryInterval;
    private final Map<String, Map<String, MaintenanceRecordViewDTO>> pending = new ConcurrentHashMap<>();
    private final Map<String, Map<String, MaintenanceTemplateViewDTO>> pendingTemplates = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public MaintenanceReplicator(RemoteCaller caller, @Value("${app.peers}") List<String> configured,
                                 @Value("${app.replication.enabled:true}") boolean enabled,
                                 @Value("${app.replication.retry-interval:5s}") Duration interval) {
        this.caller = caller;
        this.enabled = enabled;
        this.retryInterval = interval;
        peers = configured.stream().map(String::trim).filter(s -> !s.isBlank()).toList();
        peers.forEach(peer -> {
            pending.put(peer, new ConcurrentHashMap<>());
            pendingTemplates.put(peer, new ConcurrentHashMap<>());
            locks.put(peer, new ReentrantLock());
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
        log.info("Maintenance replication {} (peers: {}, retry every {})",
                enabled ? "enabled" : "disabled", peers, retryInterval);
    }

    public boolean isEnabled() { return enabled; }

    public void replicate(MaintenanceRecordViewDTO record) {
        if (!enabled) return;
        peers.forEach(peer -> {
            pending.get(peer).merge(record.globalId(), record,
                    (oldRecord, newRecord) -> oldRecord.revision() >= newRecord.revision() ? oldRecord : newRecord);
            scheduler.execute(() -> flush(peer));
        });
    }

    public void replicateTemplate(MaintenanceTemplateViewDTO template) {
        if (!enabled) return;
        peers.forEach(peer -> {
            pendingTemplates.get(peer).merge(template.globalId(), template,
                    (oldTemplate, newTemplate) -> oldTemplate.revision() >= newTemplate.revision()
                            ? oldTemplate : newTemplate);
            scheduler.execute(() -> flushTemplates(peer));
        });
    }

    public boolean replicateTemplateAcknowledged(MaintenanceTemplateViewDTO template) {
        if (!enabled || peers.isEmpty()) return false;
        peers.forEach(peer -> pendingTemplates.get(peer).merge(template.globalId(), template,
                (oldTemplate, newTemplate) -> oldTemplate.revision() >= newTemplate.revision()
                        ? oldTemplate : newTemplate));
        boolean acknowledged = false;
        for (String peer : peers) {
            if (acknowledged) {
                scheduler.execute(() -> flushTemplates(peer));
            } else {
                flushTemplates(peer);
                acknowledged = pendingTemplates.get(peer).get(template.globalId()) == null;
            }
        }
        return acknowledged;
    }

    public boolean replicateAcknowledged(MaintenanceRecordViewDTO record) {
        if (!enabled || peers.isEmpty()) return false;
        peers.forEach(peer -> pending.get(peer).merge(record.globalId(), record,
                (oldRecord, newRecord) -> oldRecord.revision() >= newRecord.revision()
                        ? oldRecord : newRecord));
        boolean acknowledged = false;
        for (String peer : peers) {
            if (acknowledged) {
                scheduler.execute(() -> flush(peer));
            } else {
                flush(peer);
                acknowledged = pending.get(peer).get(record.globalId()) == null;
            }
        }
        return acknowledged;
    }

    private void flushAll() {
        peers.forEach(peer -> {
            flush(peer);
            flushTemplates(peer);
        });
    }

    private void flush(String peer) {
        flushQueue(peer, pending.get(peer), "records",
                record -> record.globalId(),
                record -> peer + "/internal/maintenance/records/" + record.globalId() + "/replica");
    }

    private void flushTemplates(String peer) {
        flushQueue(peer, pendingTemplates.get(peer), "templates",
                template -> template.globalId(),
                template -> peer + "/internal/maintenance/templates/" + template.globalId() + "/replica");
    }

    private <T> void flushQueue(String peer, Map<String, T> queue, String kind,
                                java.util.function.Function<T, String> id,
                                java.util.function.Function<T, String> target) {
        ReentrantLock lock = locks.get(peer);
        if (lock == null || !lock.tryLock()) return;
        try {
            while (!queue.isEmpty()) {
                for (T item : List.copyOf(queue.values())) {
                    try {
                        caller.put(target.apply(item), item, Void.class);
                        queue.remove(id.apply(item), item);
                        log.info("Replicated maintenance {} {} to {}", kind, id.apply(item), peer);
                    } catch (RemoteUnavailableException e) {
                        log.warn("Maintenance {} replication to {} unavailable; {} item(s) pending",
                                kind, peer, queue.size());
                        return;
                    } catch (HttpClientErrorException e) {
                        if (e.getStatusCode().value() == 409) return;
                        queue.remove(id.apply(item), item);
                        log.warn("Maintenance {} {} to {} rejected with {}; discarded",
                                kind, id.apply(item), peer, e.getStatusCode().value());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error("Unexpected maintenance {} replication failure to {}", kind, peer, e);
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    void shutdown() { scheduler.shutdownNow(); }
}
