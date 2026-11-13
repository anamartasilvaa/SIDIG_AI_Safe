package pt.isep.psoft.aisafe.airports_routes_service.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import pt.isep.psoft.aisafe.airports_routes_service.application.AirportQueryService;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.AuditService;
import pt.isep.psoft.aisafe.airports_routes_service.application.ReplicationOutbox;
import pt.isep.psoft.aisafe.airports_routes_service.application.ReplicationTaskScheduler;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteSearchStrategy;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportViewDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RegisterAirportDTO;
import pt.isep.psoft.aisafe.airports_routes_service.domain.Airport;
import pt.isep.psoft.aisafe.airports_routes_service.domain.AirportType;
import pt.isep.psoft.aisafe.airports_routes_service.domain.IATACode;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;
import pt.isep.psoft.aisafe.airports_routes_service.readmodel.AirportReadModel;
import pt.isep.psoft.aisafe.airports_routes_service.readmodel.AirportReadRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ReplicationTaskRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteHistoryRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteRepository;
import pt.isep.psoft.aisafe.airports_routes_service.security.PeerTokenService;

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
    @Mock AirportReadRepository airportReadRepository;
    @Mock RestTemplate restTemplate;
    @Mock ReplicationOutbox outbox;
    @Mock AuditService auditService;
    @Mock ReplicationTaskRepository taskRepository;
    @Mock RouteRepository routeRepository;
    @Mock RouteHistoryRepository routeHistoryRepository;
    @Mock RouteSearchStrategy routeSearchStrategy;
    @Mock ObjectMapper objectMapper;
    @Mock PeerTokenService peerTokenService;

    private AirportService service;
    private AirportQueryService queryService;

    @BeforeEach
    void setUp() {
        service = new AirportService(
                airportRepository,
                restTemplate,
                new ObjectMapper().findAndRegisterModules(),
                outbox,
                auditService,
                List.of("http://peer"),
                1
        );
        queryService = new AirportQueryService(
                airportReadRepository,
                restTemplate,
                new ObjectMapper().findAndRegisterModules(),
                List.of("http://peer"),
                1
        );
    }

    @Test
    void doesNotForwardWhenHopLimitIsReached() {
        when(airportReadRepository.findById("LIS")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> queryService.getAirportByIataCode("LIS", 1));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void deduplicatesUsingNewestVersion() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AirportViewDTO localView = new AirportViewDTO("LIS", "Local", "Lisbon", "PT", "Europe/Lisbon",
                "INTERNATIONAL", "OPERATIONAL", List.of(), List.of(), null, null, null);
        AirportReadModel local = new AirportReadModel("LIS", "Local", "Lisbon", "PT", "OPERATIONAL",
                null, null, mapper.writeValueAsString(localView));

        when(airportReadRepository.findAll()).thenReturn(List.of(local));

        String body = mapper.writeValueAsString(
                Map.of("_embedded", Map.of("airports", List.of(
                        new AirportViewDTO("LIS", "Newest", "Lisbon", "PT", "Europe/Lisbon",
                                "INTERNATIONAL", "OPERATIONAL", List.of(), List.of(), null, null, null,
                                Instant.now().plusSeconds(3600), "peer")
                )))
        );

        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));

        List<AirportViewDTO> results = queryService.searchAirports(null, null, null);

        assertEquals(1, results.size());
        assertEquals("Newest", results.get(0).name());
    }

    @Test
    void peerFailureKeepsTaskPending() {
        ReplicationTask task = new ReplicationTask("http://peer", "POST", "/api/airports", "{}");

        when(taskRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
                eq(ReplicationTask.Status.PENDING), any(Instant.class))).thenReturn(List.of(task));
        when(peerTokenService.createToken()).thenReturn("token-de-teste");
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(Void.class)))
                .thenThrow(new ResourceAccessException("offline"));

        new ReplicationTaskScheduler(taskRepository, restTemplate, peerTokenService).sendPendingTasks();

        assertEquals(ReplicationTask.Status.PENDING, task.getStatus());
        verify(taskRepository).save(task);
    }

    @Test
    void serverErrorFromPeerBecomesUnavailable() {
        when(airportReadRepository.findById("LIS")).thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "peer", null, null, null));

        assertThrows(ResponseStatusException.class, () -> queryService.getAirportByIataCode("LIS", 0));
    }
}