package pt.isep.psoft.aisafe.maintenance.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "processed_event", uniqueConstraints = @UniqueConstraint(columnNames = {"consumer", "event_id"}))
public class ProcessedEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String consumer;
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;
    @Column(nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedEvent() {}

    public ProcessedEvent(String consumer, String eventId) {
        this.consumer = consumer;
        this.eventId = eventId;
    }
}
