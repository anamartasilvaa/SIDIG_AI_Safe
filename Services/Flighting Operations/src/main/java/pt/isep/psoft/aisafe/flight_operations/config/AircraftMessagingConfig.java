package pt.isep.psoft.aisafe.flight_operations.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.messaging.EventTypes;

/**
 * Queues for the Aircraft Service events (same names and arguments as in
 * infrastructure/rabbitmq/definitions.json, so declaring them again is harmless).
 * - fo.aircraft.&lt;instance&gt;: one per replica (publish/subscribe), keeps aircraft_ref up to date;
 * - fo.grounding: ONE queue shared by the replicas (work queue): each grounding runs once.
 */
@Configuration
public class AircraftMessagingConfig {

    public static final String GROUNDING_QUEUE = "fo.grounding";
    public static final String RK_AIRCRAFT_ALL = "aircraft.#";
    public static final String RK_AIRCRAFT_STATUS_CHANGED = "aircraft.status-changed";

    @Bean
    public TopicExchange aircraftEventsExchange() {
        return new TopicExchange(EventTypes.AIRCRAFT_EXCHANGE, true, false);
    }

    @Bean
    public Queue foAircraftQueue(@Value("${app.instance-id}") String instanceId) {
        return foQueue("fo.aircraft." + instanceId);
    }

    @Bean
    public Binding foAircraftBinding(@Qualifier("foAircraftQueue") Queue queue,
                                     @Qualifier("aircraftEventsExchange") TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(RK_AIRCRAFT_ALL);
    }

    @Bean
    public Queue foGroundingQueue() {
        return foQueue(GROUNDING_QUEUE);
    }

    @Bean
    public Binding foGroundingAircraftBinding(@Qualifier("foGroundingQueue") Queue queue,
                                              @Qualifier("aircraftEventsExchange") TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(RK_AIRCRAFT_STATUS_CHANGED);
    }

    @Bean
    public Binding foGroundingRequestedBinding(@Qualifier("foGroundingQueue") Queue queue,
                                               @Qualifier("flightsExchange") TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(EventTypes.RK_GROUNDING_REQUESTED);
    }

    private static Queue foQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .deliveryLimit(5)
                .deadLetterExchange(RabbitTopologyConfig.DLX)
                .build();
    }
}
