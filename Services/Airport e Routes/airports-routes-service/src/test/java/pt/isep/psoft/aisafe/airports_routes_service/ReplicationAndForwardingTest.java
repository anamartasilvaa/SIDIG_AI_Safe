package pt.isep.psoft.aisafe.airports_routes_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.AuditService;
import pt.isep.psoft.aisafe.airports_routes_service.application.ReplicationOutbox;
import pt.isep.psoft.aisafe.airports_routes_service.application.ReplicationTaskScheduler;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteSearchStrategy;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportViewDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RegisterAirportDTO;
import pt.isep.psoft.aisafe.airports_routes_service.domain.Airport;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;
import pt.isep.psoft.aisafe.airports_routes_service.domain.IATACode;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ReplicationTaskRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteHistoryRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplicationAndForwardingTest {
    @Mock AirportRepository airportRepository;
    @Mock RestTemplate restTemplate;
    @Mock ReplicationOutbox outbox;
    @Mock AuditService auditService;
    @Mock ReplicationTaskRepository taskRepository;
    @Mock RouteRepository routeRepository;
    @Mock RouteHistoryRepository routeHistoryRepository;
    @Mock RouteSearchStrategy routeSearchStrategy;
    @Mock ObjectMapper objectMapper;
    private AirportService service;

    @BeforeEach
    void setUp() {
        service = new AirportService(airportRepository, restTemplate, new ObjectMapper().findAndRegisterModules(), outbox,
                auditService, List.of("http://peer"), 1);
    }

    @Test
    void doesNotForwardWhenHopLimitIsReached() {
        when(airportRepository.findByIataCodeString("LIS")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.getAirportByIataCode("LIS", 1));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void deduplicatesUsingNewestVersion() throws Exception {
        Airport local = new Airport(new IATACode("LIS"), "Local", "Lisbon", "PT",
                "Europe/Lisbon", pt.isep.psoft.aisafe.airports_routes_service.domain.AirportType.INTERNATIONAL,
                new pt.isep.psoft.aisafe.airports_routes_service.application.Coordinates(1d, 2d));
        when(airportRepository.findAll()).thenReturn(List.of(local));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String body = mapper.writeValueAsString(Map.of("_embedded", Map.of("airports", List.of(
                new AirportViewDTO("LIS", "Newest", "Lisbon", "PT", "Europe/Lisbon", "INTERNATIONAL",
                        "OPERATIONAL", List.of(), List.of(), null, null, null,
                        Instant.now().plusSeconds(3600), "peer")))));
        when(restTemplate.exchange(anyString(), any(), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(mapper.readTree(body)));

        List<AirportViewDTO> results = service.searchAirports(null, null, null);

        assertEquals(1, results.size());
        assertEquals("Newest", results.get(0).name());
    }

    @Test
    void peerFailureKeepsTaskPending() {
        ReplicationTask task = new ReplicationTask("http://peer", "POST", "/api/airports", "{}");
        when(taskRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
                eq(ReplicationTask.Status.PENDING), any(Instant.class))).thenReturn(List.of(task));
        when(restTemplate.exchange(anyString(), any(), any(), eq(Void.class)))
                .thenThrow(new ResourceAccessException("offline"));

        new ReplicationTaskScheduler(taskRepository, restTemplate, new ObjectMapper()).sendPendingTasks();

        assertEquals(ReplicationTask.Status.PENDING, task.getStatus());
        assertTrue(task.getNextAttemptAt().isAfter(Instant.now().plusSeconds(4)));
        assertTrue(task.getNextAttemptAt().isBefore(Instant.now().plusSeconds(7)));
        verify(taskRepository).save(task);
    }

    @Test
    void serverErrorFromPeerBecomesUnavailable() {
        when(airportRepository.findByIataCodeString("LIS")).thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "peer", null, null, null));

        assertThrows(ResourceAccessException.class, () -> service.getAirportByIataCode("LIS", 0));
    }

    @Test
    void routeServerErrorFromPeerBecomesUnavailable() {
        RouteService routeService = new RouteService(routeRepository, airportRepository, routeHistoryRepository,
                routeSearchStrategy, restTemplate, new ObjectMapper().findAndRegisterModules(), outbox, auditService,
                List.of("http://peer"), 1);
        when(routeRepository.findByRouteId(any())).thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "peer", null, null, null));

        assertThrows(ResourceAccessException.class, () -> routeService.getRoute("RT-1", 0));
    }

    @Test
    void negativeHopsAreClamped() {
        when(airportRepository.findByIataCodeString("LIS")).thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenThrow(new ResourceAccessException("offline"));

        assertThrows(ResourceAccessException.class, () -> service.getAirportByIataCode("LIS", -10));
        verify(restTemplate).exchange(contains("/api/airports/LIS"), eq(org.springframework.http.HttpMethod.GET),
                argThat(entity -> "1".equals(entity.getHeaders().getFirst("X-P2P-Hops"))),
                eq(com.fasterxml.jackson.databind.JsonNode.class));
    }

    @Test
    void clientSuccessQueuesReplicationWithoutCallingPeer() {
        RegisterAirportDTO dto = new RegisterAirportDTO("LIS", "Lisbon", "Lisbon", "PT",
                "Europe/Lisbon", "INTERNATIONAL", 1d, 2d, List.of(), List.of(), null);
        Airport saved = new Airport(new IATACode("LIS"), "Lisbon", "Lisbon", "PT", "Europe/Lisbon",
                pt.isep.psoft.aisafe.airports_routes_service.domain.AirportType.INTERNATIONAL,
                new pt.isep.psoft.aisafe.airports_routes_service.application.Coordinates(1d, 2d));
        when(airportRepository.save(any(Airport.class))).thenReturn(saved);

        AirportViewDTO result = service.registerAirport(dto, false);

        assertEquals("LIS", result.iataCode());
        verify(outbox).enqueue(anyList(), eq("POST"), eq("/api/airports"), eq(dto));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void replicatedReceiverIsIdempotent() {
        Airport existing = new Airport(new IATACode("LIS"), "Existing", "Lisbon", "PT", "Europe/Lisbon",
                pt.isep.psoft.aisafe.airports_routes_service.domain.AirportType.INTERNATIONAL,
                new pt.isep.psoft.aisafe.airports_routes_service.application.Coordinates(1d, 2d));
        when(airportRepository.findByIataCodeString("LIS")).thenReturn(Optional.of(existing));
        RegisterAirportDTO dto = new RegisterAirportDTO("LIS", "Duplicate", "Lisbon", "PT",
                "Europe/Lisbon", "INTERNATIONAL", 1d, 2d, List.of(), List.of(), null);

        assertEquals("Existing", service.registerAirport(dto, true).name());
        verify(airportRepository, never()).save(any());
    }

    @Test
    void olderReplicationVersionIsIgnored() {
        Airport existing = new Airport(new IATACode("LIS"), "Current", "Lisbon", "PT", "Europe/Lisbon",
                pt.isep.psoft.aisafe.airports_routes_service.domain.AirportType.INTERNATIONAL,
                new pt.isep.psoft.aisafe.airports_routes_service.application.Coordinates(1d, 2d));
        existing.applyReplicationMetadata(Instant.parse("2026-01-02T00:00:00Z"), "node-b");
        when(airportRepository.findByIataCodeString("LIS")).thenReturn(Optional.of(existing));
        RegisterAirportDTO dto = new RegisterAirportDTO("LIS", "Old", "Lisbon", "PT",
                "Europe/Lisbon", "INTERNATIONAL", 1d, 2d, List.of(), List.of(), null,
                Instant.parse("2026-01-01T00:00:00Z"), "node-a");

        AirportViewDTO result = service.registerAirport(dto, true);

        assertEquals("Current", result.name());
        verify(airportRepository, never()).save(any());
    }
}
