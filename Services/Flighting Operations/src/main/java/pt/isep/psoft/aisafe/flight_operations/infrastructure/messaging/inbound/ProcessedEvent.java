package pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.inbound;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "processed_event", schema = "fo_command")
@IdClass(ProcessedEvent.Key.class)
public class ProcessedEvent {

    @Id
    @Column(length = 60)
    private String consumer;

    @Id
    @Column(name = "event_id", length = 100)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String consumer, String eventId, Instant processedAt) {
        this.consumer = consumer;
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    public static class Key implements Serializable {
        private String consumer;
        private String eventId;

        public Key() {}

        public Key(String consumer, String eventId) {
            this.consumer = consumer;
            this.eventId = eventId;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(consumer, k.consumer) && Objects.equals(eventId, k.eventId);
        }
        @Override public int hashCode() { return Objects.hash(consumer, eventId); }
    }
}