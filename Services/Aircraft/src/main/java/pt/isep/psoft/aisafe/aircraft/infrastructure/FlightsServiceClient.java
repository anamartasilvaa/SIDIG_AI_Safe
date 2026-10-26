package pt.isep.psoft.aisafe.aircraft.infrastructure;

import java.util.Map;
import java.util.List;

public class FlightsServiceClient {

    private final RemoteCaller caller;
    private final java.util.List<String> urls;
    private final String inFlightPath;
    private final String groundingPath;
    public FlightsServiceClient(RemoteCaller caller, java.util.List<String> urls,
                                String inFlightPath, String groundingPath) {
        this.caller=caller; this.urls=urls; this.inFlightPath=inFlightPath; this.groundingPath=groundingPath;
    }
    public boolean isAircraftInFlight(String regNum) {
        RemoteUnavailableException lastFailure = null;
        for (String url: urls) try {
            var flights=caller.get(url.trim()+inFlightPath.replace("{registration}",regNum.trim().toUpperCase()), new org.springframework.core.ParameterizedTypeReference<java.util.List<Map<String,Object>>>() {});
            if (flights.isPresent() && !flights.get().isEmpty()) return true;
        } catch (RemoteUnavailableException failure) {
            lastFailure = failure;
        }
        throw lastFailure != null
                ? lastFailure
                : new RemoteUnavailableException("flights://unconfigured");
    }

    public List<String> handleAircraftGrounding(String groundedRegNum, List<String> candidateRegNums) {
        RemoteUnavailableException lastFailure = null;
        for (String url: urls) try {
            caller.post(url.trim()+groundingPath.replace("{registration}",groundedRegNum.trim().toUpperCase()),
                    Map.of("candidateRegistrationNumbers", candidateRegNums), String.class);
            return List.of("Flights service notified");
        } catch (RemoteUnavailableException failure) {
            lastFailure = failure;
        }
        throw lastFailure != null
                ? lastFailure
                : new RemoteUnavailableException("flights://unconfigured");
    }
}
