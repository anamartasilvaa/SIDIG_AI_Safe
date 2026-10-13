package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PASSO 6 - Cliente para OUTRO servico que tambem esta replicado.
 * Round-robin para distribuir carga; se a replica escolhida falhar, tenta as restantes (failover).
 * Se nenhuma responder -> DependencyUnavailableException (vira 503 no handler).
 */
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
        int start = Math.floorMod(next.getAndIncrement(), Math.max(1, replicas.size()));
        for (int i = 0; i < replicas.size(); i++) {
            String replica = replicas.get((start + i) % replicas.size());
            try {
                log.debug("{} -> GET {}{}", serviceName, replica, path);
                return caller.get(replica + path, type);
            } catch (RemoteUnavailableException e) {
                log.warn("{} replica {} indisponivel, failover", serviceName, replica);
            }
        }
        throw new DependencyUnavailableException(serviceName);
    }
}
