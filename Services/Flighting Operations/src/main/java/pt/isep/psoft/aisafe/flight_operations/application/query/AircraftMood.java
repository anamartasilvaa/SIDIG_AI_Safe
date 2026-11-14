package pt.isep.psoft.aisafe.flight_operations.application.query;

/** "Mood" of an aircraft, derived only from events (read model of GET /api/v2/unicorns). */
public enum AircraftMood {
    /** active, no recent delays, light schedule */
    HAPPY,
    /** 3 or more flights in the next 24 h */
    BUSY,
    /** 2 or more flights delayed or cancelled in the last 7 days */
    GRUMPY,
    /** not ACTIVE (maintenance, inactive...) */
    SLEEPING
}
