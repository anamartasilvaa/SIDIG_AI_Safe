package pt.isep.psoft.aisafe.flight_operations.domain.reference;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceDataTest {

    private static final Instant T1 = Instant.parse("2027-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2027-01-01T11:00:00Z");

    private AircraftRef tta() {
        return AircraftRef.create("cs-tta", "A320", "active", 6000.0, 180, 3, T1);
    }

    @Test
    void createNormalisesRegistrationAndStatus() {
        AircraftRef a = tta();
        assertEquals("CS-TTA", a.getRegistrationNumber());
        assertTrue(a.isActive());
    }

    @Test
    void newerRevisionIsApplied() {
        AircraftRef a = tta();
        assertTrue(a.applyIfNewer(null, "UNDER_MAINTENANCE", null, null, 4, T2));
        assertEquals("UNDER_MAINTENANCE", a.getStatus());
        assertFalse(a.isActive());
        assertEquals(180, a.getSeatingCapacity(), "null fields keep the current value");
    }

    @Test
    void olderOrRepeatedRevisionIsIgnored() {
        AircraftRef a = tta();
        assertFalse(a.applyIfNewer(null, "INACTIVE", null, null, 2, T2), "older revision");
        assertFalse(a.applyIfNewer(null, "INACTIVE", null, null, 3, T1), "same revision, same time");
        assertEquals("ACTIVE", a.getStatus());
    }

    @Test
    void withoutRevisionTheTimeDecides() {
        AircraftRef a = tta();
        assertFalse(a.applyIfNewer(null, "INACTIVE", null, null, 0, T1), "not after the local change");
        assertTrue(a.applyIfNewer(null, "INACTIVE", null, null, 0, T2));
        assertEquals("INACTIVE", a.getStatus());
    }

    @Test
    void routeDeactivationIsApplied() {
        RouteRef r = RouteRef.create("rt-opolis", "OPO", "LIS", true, 300.0, null, 100, 1, T1);
        assertEquals("RT-OPOLIS", r.getRouteId());
        assertTrue(r.applyIfNewer(null, null, false, null, null, null, 2, T2));
        assertFalse(r.toRequirements().active());
        assertEquals(300.0, r.toRequirements().requiredRange());
    }

    @Test
    void invalidReferencesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AircraftRef.create(" ", null, "ACTIVE", null, null, 1, T1));
        assertThrows(IllegalArgumentException.class, () -> AircraftRef.create("CS-X", null, null, null, null, 1, T1));
        assertThrows(IllegalArgumentException.class, () -> RouteRef.create("RT-X", null, null, true, null, null, null, 1, null));
    }
}