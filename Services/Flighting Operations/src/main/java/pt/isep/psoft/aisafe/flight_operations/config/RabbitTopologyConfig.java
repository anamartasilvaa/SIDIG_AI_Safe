package pt.isep.psoft.aisafe.flight_operations.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    public static final String DLX = "fo.dlx";
    public static final String DEAD_LETTER_QUEUE = "fo.dead-letter";
    private static final int DELIVERY_LIMIT = 5;

    @Bean
    public FanoutExchange foDeadLetterExchange() {
        return new FanoutExchange(DLX, true, false);
    }

    @Bean
    public Queue foDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();
    }

    @Bean
    public Binding foDeadLetterBinding() {
        return BindingBuilder.bind(foDeadLetterQueue()).to(foDeadLetterExchange());
    }

    @Bean
    public FanoutExchange airportsEventsExchange(
            @Value("${app.messaging.airports-exchange:airports.events}") String name) {
        return new FanoutExchange(name, true, false);
    }

    @Bean
    public Queue foRoutesQueue(@Value("${app.instance-id}") String instanceId) {
        return QueueBuilder.durable("fo.routes." + instanceId)
                .quorum()
                .deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange(DLX)
                .build();
    }

    @Bean
    public Binding foRoutesBinding(Queue foRoutesQueue, FanoutExchange airportsEventsExchange) {
        return BindingBuilder.bind(foRoutesQueue).to(airportsEventsExchange);
    }
}