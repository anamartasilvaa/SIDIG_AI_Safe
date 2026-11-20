package pt.isep.psoft.aisafe.airports_routes_service.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Inbox (padrao "idempotent consumer"): um evento ja processado por um consumidor fica registado aqui.
 * E gravado na MESMA transacao que o efeito do evento, por isso uma mensagem repetida pelo broker
 * (entrega "pelo menos uma vez") e reconhecida e ignorada.
 */
@Entity
@Table(name = "processed_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed_event", columnNames = {"consumer", "event_id"}))
public class ProcessedEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String consumer;

    @Column(name = "event_id", nullable = false, length = 200)
    private String eventId;

    @Column(length = 50)
    private String eventType;

    @Column(nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String consumer, String eventId, String eventType) {
        this.consumer = consumer;
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getConsumer() { return consumer; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Instant getProcessedAt() { return processedAt; }
}
