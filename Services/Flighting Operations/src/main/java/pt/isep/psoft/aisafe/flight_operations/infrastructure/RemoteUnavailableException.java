package pt.isep.psoft.aisafe.flight_operations.infrastructure;

/** O destino nao respondeu (timeout, ligacao recusada, 5xx) depois de todas as tentativas. */
public class RemoteUnavailableException extends RuntimeException {
    public RemoteUnavailableException(String url, Throwable cause) {
        super("Remote unavailable: " + url, cause);
    }
}
