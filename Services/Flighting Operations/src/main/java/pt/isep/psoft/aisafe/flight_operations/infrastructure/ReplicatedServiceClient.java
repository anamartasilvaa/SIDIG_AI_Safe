package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class ReplicatedServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ReplicatedServiceClient.class);

    private final String serviceName;
    private final List<String> replicas;
    private final RemoteCaller caller;
    private final AtomicInteger next = new AtomicInteger();

    public ReplicatedServiceClient(String serviceName, List<String> replicas, RemoteCaller caller) {
        this.serviceName = serviceName;
        this.replicas = replicas.stream().map(String::trim).filter(r -> !r.isBlank()).toList();
        this.caller = caller;
    }

    public <T> Optional<T> get(String path, Class<T> type) {
        if (replicas.isEmpty()) {
            throw new DependencyUnavailableException("Service " + serviceName + " has no replicas configured.");
        }
        int start = Math.floorMod(next.getAndIncrement(), replicas.size());
        for (int i = 0; i < replicas.size(); i++) {
            String replica = replicas.get((start + i) % replicas.size());
            try {
                log.debug("{} -> GET {}{}", serviceName, replica, path);
                return caller.get(replica + path, type);
            } catch (RemoteUnavailableException e) {
                log.warn("{}: replica {} indisponivel ({}), failover", serviceName, replica, e.getMessage());
            }
        }
        throw new DependencyUnavailableException("Service " + serviceName + " unavailable (all replicas down).");
    }
}
