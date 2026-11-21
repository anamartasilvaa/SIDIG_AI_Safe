package pt.isep.psoft.aisafe.aircraft.messaging;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {
    public static final String DLX = "aircraft.dlx";
    public static final String DEAD_LETTER_QUEUE = "aircraft.dead-letter";

    @Bean
    public TopicExchange aircraftEventsExchange(
            @Value("${app.messaging.aircraft-exchange:aircraft.events}") String name) {
        return new TopicExchange(name, true, false);
    }

    @Bean
    public FanoutExchange maintenanceEventsExchange(
            @Value("${app.messaging.maintenance-exchange:maintenance.events}") String name) {
        return new FanoutExchange(name, true, false);
    }

    @Bean
    public TopicExchange flightsEventsExchange(
            @Value("${app.messaging.flights-exchange:flights.events}") String name) {
        return new TopicExchange(name, true, false);
    }

    @Bean
    public FanoutExchange aircraftDeadLetterExchange() {
        return new FanoutExchange(DLX, true, false);
    }

    @Bean
    public Queue aircraftDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();
    }

    @Bean
    public Binding aircraftDeadLetterBinding() {
        return BindingBuilder.bind(aircraftDeadLetterQueue()).to(aircraftDeadLetterExchange());
    }

    /**
     * Shared by the Aircraft replicas (competing consumers): each maintenance event is applied by one
     * replica, which replicates the new status to its peers and publishes a single aircraft event.
     */
    @Bean
    public Queue aircraftMaintenanceQueue() {
        return replicaQueue("aircraft.maintenance");
    }

    @Bean
    public Binding aircraftMaintenanceBinding(Queue aircraftMaintenanceQueue,
                                               FanoutExchange maintenanceEventsExchange) {
        return BindingBuilder.bind(aircraftMaintenanceQueue).to(maintenanceEventsExchange);
    }

    @Bean
    public Queue aircraftFlightsQueue(@Value("${app.instance-id}") String instanceId) {
        return replicaQueue("aircraft.flights." + instanceId);
    }

    @Bean
    public Binding aircraftFlightsBinding(Queue aircraftFlightsQueue,
                                          TopicExchange flightsEventsExchange) {
        return BindingBuilder.bind(aircraftFlightsQueue).to(flightsEventsExchange).with("flight.#");
    }

    private static Queue replicaQueue(String name) {
        return QueueBuilder.durable(name).quorum().deliveryLimit(5)
                .deadLetterExchange(DLX).build();
    }
}
