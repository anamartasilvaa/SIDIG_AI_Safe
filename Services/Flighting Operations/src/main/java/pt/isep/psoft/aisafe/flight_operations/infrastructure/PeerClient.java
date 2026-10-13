package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PASSO 4 - Comunicacao peer-to-peer entre replicas do Flight Operations.
 *
 * Chama SEMPRE os endpoints /internal/** dos peers, que so respondem com dados locais
 * e nunca reencaminham. E isto que elimina o forwarding circular (A -> B -> A -> ...).
 */
@Component
public class PeerClient {

    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);

    private final RemoteCaller caller;
    private final List<String> peers;

    public PeerClient(RemoteCaller caller, @Value("${app.peers}") List<String> peers) {
        this.caller = caller;
        this.peers = peers.stream().map(String::trim).filter(p -> !p.isBlank()).toList();
        log.info("Peers configurados: {}", this.peers);
    }

    /** Pergunta peer a peer e devolve o primeiro que tiver o recurso. */
    public <T> Optional<T> findFirst(String internalPath, Class<T> type) {
        for (String peer : peers) {
            try {
                log.info("FORWARD GET {}{}", peer, internalPath);
                Optional<T> found = caller.get(peer + internalPath, type);
                if (found.isPresent()) {
                    log.info("FOUND em {}", peer);
                    return found;
                }
                log.info("NOT FOUND em {}", peer);
            } catch (RemoteUnavailableException e) {
                log.warn("Peer {} indisponivel, a tentar o seguinte", peer);
            }
        }
        return Optional.empty();
    }

    /** Pergunta a TODOS os peers e junta as respostas (agregacao). */
    public <T> AggregatedResult<T> collectAll(String internalPath, ParameterizedTypeReference<List<T>> type) {
        List<T> items = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        for (String peer : peers) {
            try {
                log.info("AGGREGATE GET {}{}", peer, internalPath);
                caller.get(peer + internalPath, type).ifPresent(items::addAll);
            } catch (RemoteUnavailableException e) {
                log.warn("Peer {} indisponivel, resposta sera parcial", peer);
                unreachable.add(peer);
            }
        }
        return new AggregatedResult<>(items, unreachable);
    }
}
