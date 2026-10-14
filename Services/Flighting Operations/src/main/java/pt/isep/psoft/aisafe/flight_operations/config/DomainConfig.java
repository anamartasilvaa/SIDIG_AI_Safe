package pt.isep.psoft.aisafe.flight_operations.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.isep.psoft.aisafe.flight_operations.domain.FlightSchedulingPolicy;

import java.time.Duration;

@Configuration
public class DomainConfig {

    @Bean
    public FlightSchedulingPolicy flightSchedulingPolicy(@Value("${app.scheduling.min-gap-hours}") long minGapHours) {
        return new FlightSchedulingPolicy(Duration.ofHours(minGapHours));
    }
}
