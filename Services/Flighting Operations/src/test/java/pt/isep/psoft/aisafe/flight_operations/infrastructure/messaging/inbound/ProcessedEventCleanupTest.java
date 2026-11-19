package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessedEventCleanupTest {

    @Test
    void removesEntriesOlderThanTheRetention() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2027-03-08T10:00:00Z");
        when(jdbc.update(eq(ProcessedEventCleanup.SQL), (Object) any())).thenReturn(3);
        ProcessedEventCleanup cleanup = new ProcessedEventCleanup(jdbc, Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(7));

        assertEquals(3, cleanup.cleanup());

        verify(jdbc).update(ProcessedEventCleanup.SQL, Timestamp.from(Instant.parse("2027-03-01T10:00:00Z")));
    }
}
