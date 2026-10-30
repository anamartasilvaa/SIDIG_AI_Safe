package pt.isep.psoft.aisafe.maintenance.infrastructure;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.util.UriComponentsBuilder;
import pt.isep.psoft.aisafe.maintenance.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.maintenance.application.DTO.MaintenanceRecordViewDTO;
import pt.isep.psoft.aisafe.maintenance.application.DTO.MaintenanceTemplateViewDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class MaintenancePeerClient {
    private final RemoteCaller caller;
    private final List<String> peers;
    private final ExecutorService executor;

    public MaintenancePeerClient(RemoteCaller caller, List<String> peers, ExecutorService executor) {
        this.caller = caller;
        this.peers = peers.stream().map(String::trim).filter(p -> !p.isBlank()).toList();
        this.executor = executor;
    }

    public AggregatedResult<MaintenanceRecordViewDTO> forwardHistory(String registrationNumber) {
        String path = UriComponentsBuilder.fromPath("/internal/maintenance/records/{registrationNumber}")
                .buildAndExpand(registrationNumber.trim().toUpperCase()).encode().toUriString();
        return collect(path, new ParameterizedTypeReference<>() {});
    }

    public AggregatedResult<MaintenanceRecordViewDTO> forwardSearch(String registrationNumber, String category,
                                                                      String fromDate, String toDate) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/maintenance/records");
        if (registrationNumber != null && !registrationNumber.isBlank()) builder.queryParam("registrationNumber", registrationNumber);
        if (category != null && !category.isBlank()) builder.queryParam("category", category);
        if (fromDate != null && !fromDate.isBlank()) builder.queryParam("fromDate", fromDate);
        if (toDate != null && !toDate.isBlank()) builder.queryParam("toDate", toDate);
        return collect(builder.build().encode().toUriString(), new ParameterizedTypeReference<>() {});
    }

    public AggregatedResult<MaintenanceRecordViewDTO> collectAll() {
        return collect("/internal/maintenance/records", new ParameterizedTypeReference<>() {});
    }

    public AggregatedResult<MaintenanceTemplateViewDTO> collectTemplates() {
        return collect("/internal/maintenance/templates", new ParameterizedTypeReference<>() {});
    }

    private <T> AggregatedResult<T> collect(String path, ParameterizedTypeReference<List<T>> type) {
        Map<String, Future<List<T>>> futures = new java.util.LinkedHashMap<>();
        for (String peer : peers) {
            futures.put(peer, executor.submit(() -> caller.get(peer + path, type).orElse(List.of())));
        }
        List<T> all = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        for (Map.Entry<String, Future<List<T>>> entry : futures.entrySet()) {
            try {
                all.addAll(entry.getValue().get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unreachable.add(entry.getKey());
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RemoteUnavailableException) {
                    unreachable.add(entry.getKey());
                } else {
                    throw new IllegalStateException("Peer maintenance query failed for " + entry.getKey(), e.getCause());
                }
            }
        }
        return new AggregatedResult<>(all, unreachable);
    }
}
