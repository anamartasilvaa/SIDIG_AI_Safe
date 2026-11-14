package pt.isep.psoft.aisafe.flight_operations.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queues owned by THIS replica. Declaring them is idempotent; the broker definitions file
 * declares the same queues with the same arguments, so they exist (and keep messages)
 * even while the replica is down.
 * - quorum: replicated on a majority of the broker nodes (survives the loss of one node);
 * - delivery-limit 5 + dead-letter exchange: a message that keeps failing is isolated.
 */
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

    // ---------------- routes (Airports & Routes Service) ----------------

    /** Exchange of the Airports &amp; Routes Service (fanout, as declared by that service). */
    @Bean
    public FanoutExchange airportsEventsExchange(
            @Value("${app.messaging.airports-exchange:airports.events}") String name) {
        return new FanoutExchange(name, true, false);
    }

    @Bean
    public Queue foRoutesQueue(@Value("${app.instance-id}") String instanceId) {
        return replicaQueue("fo.routes." + instanceId);
    }

    @Bean
    public Binding foRoutesBinding(Queue foRoutesQueue, FanoutExchange airportsEventsExchange) {
        return BindingBuilder.bind(foRoutesQueue).to(airportsEventsExchange);
    }

    // ---------------- flights (every FO replica, this one included) ----------------

    /** One queue per replica: every replica receives every flight event (publish/subscribe). */
    @Bean
    public Queue foFlightsQueue(@Value("${app.instance-id}") String instanceId) {
        return replicaQueue("fo.flights." + instanceId);
    }

    @Bean
    public Binding foFlightsBinding(Queue foFlightsQueue, TopicExchange flightsExchange) {
        return BindingBuilder.bind(foFlightsQueue).to(flightsExchange).with("flight.#");
    }

    private static Queue replicaQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange(DLX)
                .build();
    }
}
