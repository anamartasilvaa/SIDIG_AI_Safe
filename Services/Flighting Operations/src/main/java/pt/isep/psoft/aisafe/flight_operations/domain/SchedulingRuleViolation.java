package pt.isep.psoft.aisafe.flight_operations.domain;

/** Violacao de uma regra de negocio do agendamento (traduzida para 409 Conflict na API). */
public class SchedulingRuleViolation extends RuntimeException {
    public SchedulingRuleViolation(String message) {
        super(message);
    }
}
