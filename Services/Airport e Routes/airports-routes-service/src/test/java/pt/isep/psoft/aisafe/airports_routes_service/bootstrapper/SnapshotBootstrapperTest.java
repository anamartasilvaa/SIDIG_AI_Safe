package pt.isep.psoft.aisafe.airports_routes_service.bootstrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportSnapshotDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RouteSnapshotDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.SnapshotDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Instancia que arranca com a BD vazia: copia os dados de outra instancia (GET /internal/snapshot). */
@ExtendWith(MockitoExtension.class)
class SnapshotBootstrapperTest {

    private static final String PEER = "https://airports-routes-2:8443";

    @Mock AirportRepository airportRepository;
    @Mock AirportService airportService;
    @Mock RouteService routeService;
    @Mock RestTemplate restTemplate;

    private SnapshotBootstrapper bootstrapper(boolean enabled) {
        return new SnapshotBootstrapper(airportRepository, airportService, routeService, restTemplate,
                List.of(PEER), enabled, Duration.ofMillis(1), 1);
    }

    private final AirportSnapshotDTO opo = new AirportSnapshotDTO("OPO", "Porto", "Porto", "Portugal",
            "Europe/Lisbon", "INTERNATIONAL", "OPERATIONAL", 41.24, -8.68, List.of(), List.of(),
            null, null, null, Instant.parse("2026-11-15T10:00:00Z"), "airports-routes-2");
    private final RouteSnapshotDTO route = new RouteSnapshotDTO("RT-1", "OPO", "OPO", 50, 300.0, 70,
            "ACTIVE", Instant.parse("2026-11-15T10:00:00Z"), "airports-routes-2");

    private void peerAnswers(SnapshotDTO snapshot) {
        when(restTemplate.exchange(eq(PEER + SnapshotBootstrapper.SNAPSHOT_PATH), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(SnapshotDTO.class))).thenReturn(ResponseEntity.ok(snapshot));
    }

    @Test
    void databaseWithDataDoesNotRequestSnapshot() {
        when(airportRepository.count()).thenReturn(5L);
        SnapshotBootstrapper b = bootstrapper(true);

        b.checkDatabaseAtStartup();
        b.onApplicationReady();

        verifyNoInteractions(restTemplate);
    }

    @Test
    void disabledSnapshotDoesNothing() {
        SnapshotBootstrapper b = bootstrapper(false);

        b.checkDatabaseAtStartup();
        b.onApplicationReady();

        verifyNoInteractions(restTemplate, airportRepository);
    }

    @Test
    void unreachablePeerMeansTryAgain() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(SnapshotDTO.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertFalse(bootstrapper(true).bootstrapOnce());
        verifyNoInteractions(airportService, routeService);
    }

    @Test
    void appliesAirportsBeforeRoutesAndFinishes() {
        peerAnswers(new SnapshotDTO(List.of(opo), List.of(route)));
        when(airportService.applySnapshot(opo)).thenReturn(true);
        when(routeService.applySnapshot(route)).thenReturn(true);

        assertTrue(bootstrapper(true).bootstrapOnce());

        InOrder order = inOrder(airportService, routeService);
        order.verify(airportService).applySnapshot(opo);
        order.verify(routeService).applySnapshot(route);
    }

    @Test
    void itemThatFailsIsTriedAgain() {
        peerAnswers(new SnapshotDTO(List.of(opo), List.of(route)));
        when(airportService.applySnapshot(opo)).thenReturn(true);
        when(routeService.applySnapshot(route)).thenThrow(new IllegalStateException("conflict"));

        assertFalse(bootstrapper(true).bootstrapOnce());
    }

    @Test
    void retriesUntilPeerAnswers() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(SnapshotDTO.class)))
                .thenThrow(new ResourceAccessException("down"))
                .thenReturn(ResponseEntity.ok(new SnapshotDTO(List.of(opo), List.of())));
        when(airportService.applySnapshot(opo)).thenReturn(true);
        SnapshotBootstrapper b = new SnapshotBootstrapper(airportRepository, airportService, routeService,
                restTemplate, List.of(PEER), true, Duration.ofMillis(1), 0);

        b.runWithRetries();

        verify(restTemplate, times(2)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SnapshotDTO.class));
        verify(airportService).applySnapshot(opo);
    }
}
