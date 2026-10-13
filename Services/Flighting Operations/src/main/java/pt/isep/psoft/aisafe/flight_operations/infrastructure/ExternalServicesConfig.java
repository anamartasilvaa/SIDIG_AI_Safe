package pt.isep.psoft.aisafe.flight_operations.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ExternalServicesConfig {

    @Bean
    public ReplicatedServiceClient routesClient(@Value("${app.services.routes.urls}") List<String> urls,
                                                RemoteCaller caller) {
        return new ReplicatedServiceClient("airports-routes", urls, caller);
    }

    @Bean
    public ReplicatedServiceClient aircraftClient(@Value("${app.services.aircraft.urls}") List<String> urls,
                                                  RemoteCaller caller) {
        return new ReplicatedServiceClient("aircraft-maintenance", urls, caller);
    }
}
