package pt.isep.psoft.aisafe.aircraft.infrastructure;
public class RemoteUnavailableException extends RuntimeException {
    public RemoteUnavailableException(String url, Throwable cause) { super("Remote unavailable: " + url, cause); }
    public RemoteUnavailableException(String url) { this(url, (Throwable) null); }
    public RemoteUnavailableException(String url, String reason) {
        super("Remote unavailable: " + url + ": " + reason);
    }
}
