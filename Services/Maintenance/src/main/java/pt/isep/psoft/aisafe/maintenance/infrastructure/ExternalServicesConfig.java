package pt.isep.psoft.aisafe.maintenance.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ExecutorService;

@Configuration
public class ExternalServicesConfig {

    @Bean
    public AircraftClient aircraftClient(
            RemoteCaller caller,
            @Value("${app.services.aircraft.urls}") List<String> urls,
            @Value("${app.services.aircraft.status-path}") String statusPath,
            @Value("${app.services.aircraft.lookup-path}") String lookupPath,
            @Value("${app.services.aircraft.search-path}") String searchPath) {
        return new AircraftClient(caller, urls, statusPath, lookupPath, searchPath);
    }

    @Bean
    public MaintenancePeerClient maintenancePeerClient(
            RemoteCaller caller,
            @Value("${app.peers}") List<String> peers,
            @Qualifier("peerExecutor") ExecutorService executor) {
        return new MaintenancePeerClient(caller, peers, executor);
    }
}
