package pt.isep.psoft.aisafe.flight_operations.infrastructure;
public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message) {
        super(message);
    }
}
