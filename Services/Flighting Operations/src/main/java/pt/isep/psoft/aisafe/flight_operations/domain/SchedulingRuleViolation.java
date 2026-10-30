package pt.isep.psoft.aisafe.flight_operations.domain;

public class SchedulingRuleViolation extends RuntimeException {
    public SchedulingRuleViolation(String message) {
        super(message);
    }
}
