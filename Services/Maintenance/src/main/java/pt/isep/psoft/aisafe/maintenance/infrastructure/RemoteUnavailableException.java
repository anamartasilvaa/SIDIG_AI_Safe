package pt.isep.psoft.aisafe.maintenance.infrastructure;

public class RemoteUnavailableException extends RuntimeException {
    public RemoteUnavailableException(String url, Throwable cause) {
        super("Remote call failed for " + url, cause);
    }

    public RemoteUnavailableException(String url, String reason) {
        super("Remote call failed for " + url + ": " + reason);
    }
}
