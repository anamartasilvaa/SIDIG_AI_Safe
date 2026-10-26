package pt.isep.psoft.aisafe.aircraft.infrastructure;

import org.springframework.core.ParameterizedTypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.isep.psoft.aisafe.aircraft.application.DTO.AircraftViewDTO;
import pt.isep.psoft.aisafe.aircraft.application.DTO.AggregatedResult;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.concurrent.*;

public class AircraftPeerClient {

    private static final Logger log = LoggerFactory.getLogger(AircraftPeerClient.class);
    private final RemoteCaller caller;
    private final List<String> peers;
    private final ExecutorService executor;
    public AircraftPeerClient(RemoteCaller caller, List<String> peers, ExecutorService executor) {
        this.caller=caller; this.peers=peers.stream().map(String::trim).filter(p -> !p.isBlank()).toList();
        this.executor = executor;
    }
    public Optional<AircraftViewDTO> forwardGetAircraftByRegNum(String regNum) {
        String path = UriComponentsBuilder.fromPath("/internal/aircraft/instances/{registration}")
                .buildAndExpand(regNum.trim().toUpperCase()).encode().toUriString();
        CompletionService<Optional<AircraftViewDTO>> completion = new ExecutorCompletionService<>(executor);
        for (String peer: peers) completion.submit(() -> {
            log.info("FORWARD GET {}{}", peer, path);
            return caller.get(peer+path, AircraftViewDTO.class);
        });
        for (int i = 0; i < peers.size(); i++) try {
            Optional<AircraftViewDTO> result = completion.take().get();
            if (result.isPresent()) return result;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); break;
        } catch (ExecutionException | RemoteUnavailableException e) { log.warn("Peer unavailable: {}", e.getMessage()); }
        return Optional.empty();
    }

    public AggregatedResult<AircraftViewDTO> forwardSearchAircrafts(String model, String status, Integer year) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/aircraft/instances");
        if (model != null && !model.isBlank()) builder.queryParam("model", model);
        if (status != null && !status.isBlank()) builder.queryParam("status", status);
        if (year != null) builder.queryParam("year", year);
        String path = builder.build().encode().toUriString();
        Map<Future<Optional<List<AircraftViewDTO>>>, String> futures = new LinkedHashMap<>();
        for (String peer : peers) {
            futures.put(executor.submit(
                    () -> caller.get(peer + path, new ParameterizedTypeReference<List<AircraftViewDTO>>() {})), peer);
        }
        List<AircraftViewDTO> all = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        futures.forEach((future, peer) -> {
            try {
                future.get().ifPresent(all::addAll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unreachable.add(peer);
            } catch (ExecutionException | RuntimeException e) {
                log.warn("Peer {} unavailable: {}", peer, e.getMessage());
                unreachable.add(peer);
            }
        });
        return new AggregatedResult<>(all, unreachable);
    }
}
