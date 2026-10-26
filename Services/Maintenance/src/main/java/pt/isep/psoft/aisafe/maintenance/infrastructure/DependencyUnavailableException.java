package pt.isep.psoft.aisafe.maintenance.infrastructure;

public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message) {
        super(message);
    }

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
