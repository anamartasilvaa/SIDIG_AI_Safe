package pt.isep.psoft.aisafe.flight_operations.messaging.outbox;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "outbox_event", schema = "fo_command")
public class OutboxEvent {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false, length = 36)
    private String eventId;

    @Column(name = "exchange_name", nullable = false, updatable = false)
    private String exchangeName;

    @Column(name = "routing_key", nullable = false, updatable = false)
    private String routingKey;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(nullable = false, updatable = false, length = 100000)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    protected OutboxEvent() {}

    public OutboxEvent(String eventId, String exchangeName, String routingKey, String eventType,
                       String aggregateId, String correlationId, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.exchangeName = exchangeName;
        this.routingKey = routingKey;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.correlationId = correlationId;
        this.payload = payload;
        this.createdAt = createdAt;
        this.attempts = 0;
    }

    public void markSent(Instant when) {
        this.sentAt = when;
        this.attempts++;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null
                : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getExchangeName() { return exchangeName; }
    public String getRoutingKey() { return routingKey; }
    public String getEventType() { return eventType; }
    public String getAggregateId() { return aggregateId; }
    public String getCorrelationId() { return correlationId; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
}