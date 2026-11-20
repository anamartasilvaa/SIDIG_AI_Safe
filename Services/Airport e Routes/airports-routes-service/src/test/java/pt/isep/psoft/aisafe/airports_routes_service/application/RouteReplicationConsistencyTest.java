package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.CreateRouteDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.UpdateRouteDTO;
import pt.isep.psoft.aisafe.airports_routes_service.domain.Airport;
import pt.isep.psoft.aisafe.airports_routes_service.domain.AirportType;
import pt.isep.psoft.aisafe.airports_routes_service.domain.IATACode;
import pt.isep.psoft.aisafe.airports_routes_service.domain.Route;
import pt.isep.psoft.aisafe.airports_routes_service.domain.RouteId;
import pt.isep.psoft.aisafe.airports_routes_service.domain.RouteStatus;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteHistoryRepository;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.RouteRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Modelo de consistencia entre instancias (eventos AMQP aplicados com isReplication = true):
 * last-writer-wins por lastModified, desempate pelo originNodeId maior, eventos repetidos ou
 * antigos nao alteram o estado, e eventos aplicados nunca sao republicados.
 */
@ExtendWith(MockitoExtension.class)
class RouteReplicationConsistencyTest {

    private static final String NODE_A = "airports-routes-1";
    private static final String NODE_B = "airports-routes-2";

    @Mock RouteRepository routeRepository;
    @Mock AirportRepository airportRepository;
    @Mock RouteHistoryRepository routeHistoryRepository;
    @Mock RouteSearchStrategy routeSearchStrategy;
    @Mock RestTemplate restTemplate;
    @Mock ReplicationOutbox outbox;
    @Mock AuditService auditService;

    private RouteService service;
    private Airport lis;
    private Airport opo;
    private Route route;
    private Instant base;

    @BeforeEach
    void setUp() {
        service = new RouteService(routeRepository, airportRepository, routeHistoryRepository, routeSearchStrategy,
                restTemplate, new ObjectMapper().findAndRegisterModules(), outbox, auditService, List.of("http://peer"), 1);
        ReflectionTestUtils.setField(service, "nodeId", NODE_A);

        lis = new Airport(new IATACode("LIS"), "Humberto Delgado", "Lisboa", "Portugal", "Europe/Lisbon",
                AirportType.INTERNATIONAL, new Coordinates(38.77, -9.13));
        opo = new Airport(new IATACode("OPO"), "Francisco Sa Carneiro", "Porto", "Portugal", "Europe/Lisbon",
                AirportType.INTERNATIONAL, new Coordinates(41.24, -8.68));
        route = new Route(new RouteId("RT-1"), lis, opo, 50, 300.0, 50);
        base = Instant.parse("2026-11-15T10:00:00Z");
        route.applyReplicationMetadata(base, NODE_A);

        lenient().when(routeRepository.findByRouteId(new RouteId("RT-1"))).thenReturn(Optional.of(route));
        lenient().when(routeRepository.save(any(Route.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(airportRepository.findByIataCodeString("LIS")).thenReturn(Optional.of(lis));
        lenient().when(airportRepository.findByIataCodeString("OPO")).thenReturn(Optional.of(opo));
    }

    // ---------- deactivate ----------

    @Test
    void newerDeactivationFromAnotherInstanceIsApplied() {
        Instant later = base.plusSeconds(5);

        service.deactivateRoute("RT-1", true, later, NODE_B);

        assertEquals(RouteStatus.INACTIVE, route.getStatus());
        assertEquals(later, route.getLastModified());
        assertEquals(NODE_B, route.getOriginNodeId());
    }

    @Test
    void olderDeactivationIsIgnored() {
        service.deactivateRoute("RT-1", true, base.minusSeconds(5), NODE_B);

        assertEquals(RouteStatus.ACTIVE, route.getStatus());
        assertEquals(base, route.getLastModified());
        verify(routeRepository, never()).save(any());
    }

    @Test
    void sameDeactivationEventTwiceIsIdempotent() {
        Instant later = base.plusSeconds(5);

        service.deactivateRoute("RT-1", true, later, NODE_B);
        assertDoesNotThrow(() -> service.deactivateRoute("RT-1", true, later, NODE_B));

        assertEquals(RouteStatus.INACTIVE, route.getStatus());
        verify(routeRepository, times(1)).save(route);
    }

    @Test
    void replicatedEventsAreNeverRepublished() {
        service.deactivateRoute("RT-1", true, base.plusSeconds(5), NODE_B);
        service.updateRoute("RT-1", new UpdateRouteDTO(60, 400.0, 80), true, base.plusSeconds(10), NODE_B);

        verifyNoInteractions(outbox);
    }

    // ---------- update ----------

    @Test
    void newerUpdateIsApplied() {
        service.updateRoute("RT-1", new UpdateRouteDTO(70, 500.0, 90), true, base.plusSeconds(1), NODE_B);

        assertEquals(70, route.getEstimatedFlightTime());
        assertEquals(500.0, route.getMinimumRange());
        assertEquals(90, route.getMinimumCapacity());
    }

    @Test
    void olderUpdateIsIgnored() {
        service.updateRoute("RT-1", new UpdateRouteDTO(70, 500.0, 90), true, base.minusSeconds(1), NODE_B);

        assertEquals(50, route.getEstimatedFlightTime());
        verify(routeRepository, never()).save(any());
    }

    @Test
    void onATieTheLargerOriginNodeIdWins() {
        // NODE_B ("...-2") > NODE_A ("...-1"): applied
        service.updateRoute("RT-1", new UpdateRouteDTO(70, 500.0, 90), true, base, NODE_B);
        assertEquals(70, route.getEstimatedFlightTime());
        assertEquals(NODE_B, route.getOriginNodeId());

        // same timestamp, smaller node id: ignored
        service.updateRoute("RT-1", new UpdateRouteDTO(10, 100.0, 10), true, base, NODE_A);
        assertEquals(70, route.getEstimatedFlightTime());
    }

    @Test
    void concurrentUpdatesConvergeToTheSameStateWhateverTheArrivalOrder() {
        UpdateRouteDTO first = new UpdateRouteDTO(60, 400.0, 60);
        UpdateRouteDTO second = new UpdateRouteDTO(80, 600.0, 80);
        Instant t1 = base.plusSeconds(1);
        Instant t2 = base.plusSeconds(2);

        // arrival order: older, then newer
        service.updateRoute("RT-1", first, true, t1, NODE_A);
        service.updateRoute("RT-1", second, true, t2, NODE_B);
        int inOrder = route.getEstimatedFlightTime();

        // reset and apply in the opposite order
        route.applyReplicationData(50, 300.0, 50, RouteStatus.ACTIVE);
        route.applyReplicationMetadata(base, NODE_A);
        service.updateRoute("RT-1", second, true, t2, NODE_B);
        service.updateRoute("RT-1", first, true, t1, NODE_A);
        int reversed = route.getEstimatedFlightTime();

        assertEquals(80, inOrder);
        assertEquals(inOrder, reversed);
    }

    // ---------- local command ----------

    @Test
    void localCommandIsStampedWithThisNodeAndEnqueuedOnce() {
        service.updateRoute("RT-1", new UpdateRouteDTO(70, 500.0, 90), false);

        assertEquals(NODE_A, route.getOriginNodeId());
        assertTrue(route.getLastModified().isAfter(base));
        verify(outbox, times(1)).enqueue(anyList(), eq("PATCH"), eq("/api/routes/RT-1"), any(),
                eq(route.getLastModified()), eq(NODE_A));
    }

    @Test
    void localDeactivationEnqueuesTheDeactivateEvent() {
        service.deactivateRoute("RT-1", false);

        assertEquals(RouteStatus.INACTIVE, route.getStatus());
        verify(outbox).enqueue(anyList(), eq("PATCH"), eq("/api/routes/RT-1/deactivate"), any(), any(), eq(NODE_A));
    }

    // ---------- create ----------

    @Test
    void replicatedCreateOfAnExistingRouteWithNewerDataOverwritesIt() {
        CreateRouteDTO dto = new CreateRouteDTO("RT-1", "LIS", "OPO", 65, 450.0, 70, base.plusSeconds(3), NODE_B);

        service.createRoute(dto, true, base.plusSeconds(3), NODE_B);

        assertEquals(65, route.getEstimatedFlightTime());
        assertEquals(NODE_B, route.getOriginNodeId());
        verifyNoInteractions(outbox);
    }

    @Test
    void replicatedCreateOfAnExistingRouteWithOlderDataIsIgnored() {
        CreateRouteDTO dto = new CreateRouteDTO("RT-1", "LIS", "OPO", 65, 450.0, 70, base.minusSeconds(3), NODE_B);

        service.createRoute(dto, true, base.minusSeconds(3), NODE_B);

        assertEquals(50, route.getEstimatedFlightTime());
        verify(routeRepository, never()).save(any());
    }

    // ---------- history (regression: ROUTE_UPDATED right after ROUTE_CREATED went to the DLQ) ----------

    @Test
    void replicatedCreateThenReplicatedUpdateKeepsAValidHistory() {
        RouteId newId = new RouteId("RT-9");
        Instant created = Instant.now().minusSeconds(3);   // event time on the origin instance
        Instant updated = created.plusSeconds(1);           // still before "now" on this instance
        when(routeRepository.findByRouteId(newId)).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<pt.isep.psoft.aisafe.airports_routes_service.domain.RouteHistory> history =
                org.mockito.ArgumentCaptor.forClass(pt.isep.psoft.aisafe.airports_routes_service.domain.RouteHistory.class);

        service.createRoute(new CreateRouteDTO("RT-9", "LIS", "OPO", 50, 300.0, 50, created, NODE_B), true, created, NODE_B);
        verify(routeHistoryRepository).save(history.capture());
        pt.isep.psoft.aisafe.airports_routes_service.domain.RouteHistory createdEntry = history.getValue();
        assertEquals(java.time.LocalDateTime.ofInstant(created, java.time.ZoneOffset.UTC), createdEntry.getStartDate());

        Route saved = new Route(newId, lis, opo, 50, 300.0, 50);
        saved.applyReplicationMetadata(created, NODE_B);
        when(routeRepository.findByRouteId(newId)).thenReturn(Optional.of(saved));
        when(routeHistoryRepository.findFirstByRouteIdAndEndDateIsNull("RT-9")).thenReturn(Optional.of(createdEntry));

        assertDoesNotThrow(() ->
                service.updateRoute("RT-9", new UpdateRouteDTO(77, 350.0, 60), true, updated, NODE_B));
        assertEquals(77, saved.getEstimatedFlightTime());
        assertTrue(createdEntry.getEndDate().isAfter(createdEntry.getStartDate()));
    }

    @Test
    void outOfOrderHistoryNeverEndsBeforeItStarts() {
        pt.isep.psoft.aisafe.airports_routes_service.domain.RouteHistory open =
                new pt.isep.psoft.aisafe.airports_routes_service.domain.RouteHistory("RT-1", "CREATED",
                        java.time.LocalDateTime.ofInstant(base.plusSeconds(60), java.time.ZoneOffset.UTC));
        when(routeHistoryRepository.findFirstByRouteIdAndEndDateIsNull("RT-1")).thenReturn(Optional.of(open));

        assertDoesNotThrow(() ->
                service.updateRoute("RT-1", new UpdateRouteDTO(70, 500.0, 90), true, base.plusSeconds(5), NODE_B));
        assertTrue(open.getEndDate().isAfter(open.getStartDate()));
    }

    @Test
    void localCreateOfAnExistingRouteIsRejected() {
        CreateRouteDTO dto = new CreateRouteDTO("RT-1", "LIS", "OPO", 65, 450.0, 70, null, null);

        assertThrows(IllegalStateException.class, () -> service.createRoute(dto, false));
        verifyNoInteractions(outbox);
    }
}
