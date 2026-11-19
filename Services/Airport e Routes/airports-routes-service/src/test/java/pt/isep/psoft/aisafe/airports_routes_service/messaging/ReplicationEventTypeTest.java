package pt.isep.psoft.aisafe.airports_routes_service.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationEventTypeTest {

    @Test
    void mapsAirportOperations() {
        assertEquals(ReplicationEventType.AIRPORT_CREATED,
                ReplicationEventType.from("POST", "/api/airports"));
        assertEquals(ReplicationEventType.AIRPORT_DETAILS_UPDATED,
                ReplicationEventType.from("PATCH", "/api/airports/LIS/details"));
        assertEquals(ReplicationEventType.AIRPORT_STATUS_CHANGED,
                ReplicationEventType.from("PATCH", "/api/airports/LIS/status"));
        assertEquals(ReplicationEventType.AIRPORT_IMAGE_UPDATED,
                ReplicationEventType.from("PATCH", "/api/airports/LIS/image"));
    }

    @Test
    void mapsRouteOperations() {
        assertEquals(ReplicationEventType.ROUTE_CREATED,
                ReplicationEventType.from("POST", "/api/routes"));
        assertEquals(ReplicationEventType.ROUTE_UPDATED,
                ReplicationEventType.from("PATCH", "/api/routes/RT-1"));
        assertEquals(ReplicationEventType.ROUTE_DEACTIVATED,
                ReplicationEventType.from("PATCH", "/api/routes/RT-1/deactivate"));
    }

    @Test
    void rejectsUnknownOperation() {
        assertThrows(IllegalArgumentException.class,
                () -> ReplicationEventType.from("DELETE", "/api/airports/LIS"));
    }

    @Test
    void extractsResourceId() {
        assertEquals("LIS", ReplicationEventType.resourceId("/api/airports/LIS/status"));
        assertEquals("RT-1", ReplicationEventType.resourceId("/api/routes/RT-1"));
        assertNull(ReplicationEventType.resourceId("/api/airports"));
    }
}
