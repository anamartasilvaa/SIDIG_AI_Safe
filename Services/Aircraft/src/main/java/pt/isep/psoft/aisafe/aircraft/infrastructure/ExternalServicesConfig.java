package pt.isep.psoft.aisafe.aircraft.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ExecutorService;

@Configuration
public class ExternalServicesConfig {

    @Bean
    public FlightsServiceClient flightsServiceClient(
            RemoteCaller caller,
            @Value("${app.services.flights.urls}") List<String> urls,
            @Value("${app.services.flights.in-flight-path}") String inFlightPath,
            @Value("${app.services.flights.grounding-path}") String groundingPath) {
        return new FlightsServiceClient(caller, urls, inFlightPath, groundingPath);
    }

    @Bean
    public AirportsRoutesClient airportsRoutesClient(
            RemoteCaller caller,
            @Value("${app.services.routes.urls}") List<String> urls,
            @Value("${app.services.routes.path}") String path) {
        return new AirportsRoutesClient(caller, urls, path);
    }

    @Bean
    public AircraftPeerClient aircraftPeerClient(
            RemoteCaller caller,
            @Value("${app.peers}") List<String> peers,
            @Qualifier("peerExecutor") ExecutorService executor) {
        return new AircraftPeerClient(caller, peers, executor);
    }
}
