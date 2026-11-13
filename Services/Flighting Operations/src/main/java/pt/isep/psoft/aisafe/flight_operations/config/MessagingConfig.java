package pt.isep.psoft.aisafe.flight_operations.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.EventTypes;

import java.time.Clock;

@Configuration
@EnableScheduling
public class MessagingConfig {

    @Bean
    public ObjectMapper eventMapper() {
        return new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TopicExchange flightsExchange() {
        return new TopicExchange(EventTypes.FLIGHTS_EXCHANGE, true, false);
    }
}