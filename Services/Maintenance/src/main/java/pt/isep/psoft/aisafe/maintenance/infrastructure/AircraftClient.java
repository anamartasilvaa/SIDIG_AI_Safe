package pt.isep.psoft.aisafe.maintenance.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import pt.isep.psoft.aisafe.maintenance.application.DTO.AircraftSummaryDTO;
import pt.isep.psoft.aisafe.maintenance.infrastructure.RemoteCaller;
import pt.isep.psoft.aisafe.maintenance.infrastructure.RemoteUnavailableException;

import java.util.List;
import java.util.Map;
import org.springframework.web.util.UriComponentsBuilder;

public class AircraftClient {
    private static final Logger log = LoggerFactory.getLogger(AircraftClient.class);

    private final RemoteCaller caller;
    private final List<String> urls;
    private final String statusPath;
    private final String lookupPath;
    private final String searchPath;

    public AircraftClient(RemoteCaller caller,
                          List<String> urls,
                          String statusPath,
                          String lookupPath,
                          String searchPath) {
        this.caller = caller;
        this.urls = urls.stream().map(String::trim).filter(url -> !url.isBlank()).toList();
        this.statusPath = statusPath;
        this.lookupPath = lookupPath;
        this.searchPath = searchPath;
    }

    public String getAircraftModelName(String registrationNumber) {
        Map<String, Object> body = getFromAircraft(lookupPath, registrationNumber,
                new ParameterizedTypeReference<Map<String, Object>>() {})
                .orElseThrow(() -> new IllegalStateException("Aircraft service could not resolve model for " + registrationNumber));
        Object modelName = body.get("modelName");
            if (modelName == null) {
                modelName = body.get("aircraftModel");
            }
            if (modelName == null) {
                modelName = body.get("model");
            }
            if (modelName == null) {
                throw new IllegalStateException("Aircraft service response for " + registrationNumber + " is missing model data");
            }
            return modelName.toString();
    }

    public void updateMaintenanceStatus(String registrationNumber, String status) {
        Map<String, String> body = Map.of("status", status);
        RemoteUnavailableException lastFailure = null;
        for (String baseUrl : urls) {
            try {
                caller.patch(buildUrl(baseUrl, statusPath, registrationNumber), body, Map.class);
                return;
            } catch (RemoteUnavailableException e) {
                lastFailure = e;
            }
        }
        throw new IllegalStateException("Aircraft service rejected maintenance status update for "
                + registrationNumber, lastFailure);
    }

    public List<Map<String, Object>> listAircraftSummaries() {
        try {
            return getFromAircraft(searchPath, null,
                    new ParameterizedTypeReference<List<AircraftSummaryDTO>>() {})
                    .orElseThrow(() -> new IllegalStateException("Aircraft service returned no aircraft summaries"))
                    .stream().map(summary -> Map.<String, Object>of(
                            "registrationNumber", summary.registrationNumber(),
                            "modelName", summary.modelName(), "status", summary.status())).toList();
        } catch (RemoteUnavailableException e) {
            log.warn("Could not fetch aircraft summaries from Aircraft service: {}", e.getMessage());
            throw new IllegalStateException("Aircraft service unavailable for maintenance alert generation", e);
        }
    }

    private <T> java.util.Optional<T> getFromAircraft(String path, String registrationNumber,
                                                       ParameterizedTypeReference<T> type) {
        RemoteUnavailableException lastFailure = null;
        for (String baseUrl : urls) {
            try {
                return caller.get(buildUrl(baseUrl, path, registrationNumber), type);
            } catch (RemoteUnavailableException e) {
                lastFailure = e;
            }
        }
        throw lastFailure != null ? lastFailure
                : new RemoteUnavailableException("aircraft://unconfigured", "no replicas configured");
    }

    private String buildUrl(String baseUrl, String pathTemplate, String registrationNumber) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl).path(pathTemplate);
        if (registrationNumber == null) {
            return builder.toUriString();
        }
        return builder.buildAndExpand(registrationNumber.trim().toUpperCase()).toUriString();
    }
}
