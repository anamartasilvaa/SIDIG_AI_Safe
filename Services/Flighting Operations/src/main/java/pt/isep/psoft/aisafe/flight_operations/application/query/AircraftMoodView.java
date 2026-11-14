package pt.isep.psoft.aisafe.flight_operations.application.query;

import jakarta.persistence.*;

import java.time.Instant;

/** Read model (schema fo_query) behind GET /api/v2/unicorns. Written only by the MoodProjector. */
@Entity
@Table(name = "aircraft_mood_view", schema = "fo_query")
public class AircraftMoodView {

    @Id
    @Column(name = "registration_number", length = 20)
    private String registrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AircraftMood mood;

    @Column(length = 200)
    private String reason;

    @Column(name = "delayed_or_canceled", nullable = false)
    private int delayedOrCanceled;

    @Column(name = "flights_next24h", nullable = false)
    private int flightsNext24h;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AircraftMoodView() {}

    public AircraftMoodView(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public void update(AircraftMood mood, String reason, int delayedOrCanceled, int flightsNext24h, Instant now) {
        this.mood = mood;
        this.reason = reason;
        this.delayedOrCanceled = delayedOrCanceled;
        this.flightsNext24h = flightsNext24h;
        this.updatedAt = now;
    }

    public String getRegistrationNumber() { return registrationNumber; }
    public AircraftMood getMood() { return mood; }
    public String getReason() { return reason; }
    public int getDelayedOrCanceled() { return delayedOrCanceled; }
    public int getFlightsNext24h() { return flightsNext24h; }
    public Instant getUpdatedAt() { return updatedAt; }
}
