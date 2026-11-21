package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportSnapshotDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.FacilityDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RouteSnapshotDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RunwayDTO;
import pt.isep.psoft.aisafe.airports_routes_service.domain.*;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteHistoryRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Snapshot de uma instancia que arranca com a BD vazia: os dados da outra instancia sao copiados
 * sem gerar eventos, com last-writer-wins, e os dados iniciais locais sao substituidos pelos reais.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotApplyTest {

    private static final String PEER = "airports-routes-2";

    @Mock AirportRepository airportRepository;
    @Mock RouteRepository routeRepository;
    @Mock RouteHistoryRepository routeHistoryRepository;
    @Mock RouteSearchStrategy routeSearchStrategy;
    @Mock RestTemplate restTemplate;
    @Mock ReplicationOutbox outbox;
    @Mock AuditService auditService;

    private AirportService airportService;
    private RouteService routeService;
    private final Instant base = Instant.parse("2026-11-15T10:00:00Z");

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        airportService = new AirportService(airportRepository, restTemplate, mapper, outbox, auditService,
                List.of("http://peer"), 0);
        routeService = new RouteService(routeRepository, airportRepository, routeHistoryRepository, routeSearchStrategy,
                restTemplate, mapper, outbox, auditService, List.of("http://peer"), 0);
        lenient().when(airportRepository.save(any(Airport.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(routeRepository.save(any(Route.class))).thenAnswer(i -> i.getArgument(0));
    }

    private AirportSnapshotDTO opoSnapshot(String status, Instant lastModified, String origin) {
        return new AirportSnapshotDTO("OPO", "Francisco Sa Carneiro", "Porto", "Portugal", "Europe/Lisbon",
                "INTERNATIONAL", status, 41.24, -8.68,
                List.of(new RunwayDTO("17/35", 3480.0, "North-South")),
                List.of(new FacilityDTO("Terminal", "Terminal 1")),
                null, "06:00-23:00", "info@opo.pt", lastModified, origin);
    }

    private Airport airport(String iata, Instant lastModified, String origin) {
        Airport a = new Airport(new IATACode(iata), iata, "City", "Portugal", "Europe/Lisbon",
                AirportType.INTERNATIONAL, new Coordinates(40.0, -8.0));
        if (lastModified != null) a.applyReplicationMetadata(lastModified, origin);
        return a;
    }

    @Test
    void missingAirportIsCreatedWithAllDataAndNoEvent() {
        when(airportRepository.findByIataCodeString("OPO")).thenReturn(Optional.empty());

        assertTrue(airportService.applySnapshot(opoSnapshot("CLOSED", base, PEER)));

        ArgumentCaptor<Airport> saved = ArgumentCaptor.forClass(Airport.class);
        verify(airportRepository).save(saved.capture());
        Airport a = saved.getValue();
        assertEquals(AirportStatus.CLOSED, a.getStatus());
        assertEquals(1, a.getRunways().size());
        assertEquals(1, a.getFacilities().size());
        assertEquals("06:00-23:00", a.getOperationalHours());
        assertEquals(base, a.getLastModified());
        assertEquals(PEER, a.getOriginNodeId());
        verifyNoInteractions(outbox);
    }

    @Test
    void seedDataIsAlwaysReplacedBySnapshot() {
        // Dado inicial criado no arranque: originNodeId "local" e lastModified mais recente do que o snapshot.
        Airport seed = airport("OPO", null, null);
        when(airportRepository.findByIataCodeString("OPO")).thenReturn(Optional.of(seed));

        assertTrue(airportService.applySnapshot(opoSnapshot("CLOSED", base, PEER)));

        assertEquals(AirportStatus.CLOSED, seed.getStatus());
        assertEquals(PEER, seed.getOriginNodeId());
    }

    @Test
    void olderSnapshotDoesNotOverwriteNewerEventAlreadyApplied() {
        Airport current = airport("OPO", base.plusSeconds(10), "airports-routes-1");
        when(airportRepository.findByIataCodeString("OPO")).thenReturn(Optional.of(current));

        assertFalse(airportService.applySnapshot(opoSnapshot("CLOSED", base, PEER)));

        assertEquals(AirportStatus.OPERATIONAL, current.getStatus());
        verify(airportRepository, never()).save(any());
    }

    @Test
    void missingInactiveRouteIsCreatedWithHistoryAndNoEvent() {
        when(routeRepository.findByRouteId(new RouteId("RT-1"))).thenReturn(Optional.empty());
        when(airportRepository.findByIataCodeString("LIS")).thenReturn(Optional.of(airport("LIS", base, PEER)));
        when(airportRepository.findByIataCodeString("OPO")).thenReturn(Optional.of(airport("OPO", base, PEER)));

        assertTrue(routeService.applySnapshot(new RouteSnapshotDTO(
                "RT-1", "LIS", "OPO", 50, 300.0, 70, "INACTIVE", base, PEER)));

        ArgumentCaptor<Route> saved = ArgumentCaptor.forClass(Route.class);
        verify(routeRepository).save(saved.capture());
        assertEquals(RouteStatus.INACTIVE, saved.getValue().getStatus());
        assertEquals(70, saved.getValue().getMinimumCapacity());
        assertEquals(base, saved.getValue().getLastModified());
        ArgumentCaptor<RouteHistory> history = ArgumentCaptor.forClass(RouteHistory.class);
        verify(routeHistoryRepository, times(2)).save(history.capture());
        assertEquals(List.of("CREATED", "DEACTIVATED"),
                history.getAllValues().stream().map(RouteHistory::getAction).toList());
        verifyNoInteractions(outbox);
    }

    @Test
    void olderRouteSnapshotIsIgnored() {
        Route current = new Route(new RouteId("RT-1"), airport("LIS", base, PEER), airport("OPO", base, PEER),
                50, 300.0, 70);
        current.applyReplicationMetadata(base.plusSeconds(10), "airports-routes-1");
        when(routeRepository.findByRouteId(new RouteId("RT-1"))).thenReturn(Optional.of(current));

        assertFalse(routeService.applySnapshot(new RouteSnapshotDTO(
                "RT-1", "LIS", "OPO", 99, 999.0, 99, "INACTIVE", base, PEER)));

        assertEquals(RouteStatus.ACTIVE, current.getStatus());
        verify(routeRepository, never()).save(any());
    }
}
