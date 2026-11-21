package pt.isep.psoft.aisafe.maintenance.messaging;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {
    public static final String DLX = "maintenance.dlx";

    @Bean
    public FanoutExchange maintenanceEventsExchange(
            @Value("${app.messaging.maintenance-exchange:maintenance.events}") String name) {
        return new FanoutExchange(name, true, false);
    }

    @Bean
    public TopicExchange aircraftEventsExchange(
            @Value("${app.messaging.aircraft-exchange:aircraft.events}") String name) {
        return new TopicExchange(name, true, false);
    }

    @Bean
    public FanoutExchange maintenanceDeadLetterExchange() {
        return new FanoutExchange(DLX, true, false);
    }

    @Bean
    public Queue maintenanceDeadLetterQueue() {
        return QueueBuilder.durable("maintenance.dead-letter").quorum().build();
    }

    @Bean
    public Binding maintenanceDeadLetterBinding() {
        return BindingBuilder.bind(maintenanceDeadLetterQueue()).to(maintenanceDeadLetterExchange());
    }

    @Bean
    public Queue maintenanceAircraftQueue(@Value("${app.instance-id}") String instanceId) {
        return QueueBuilder.durable("maintenance.aircraft." + instanceId).quorum().deliveryLimit(5)
                .deadLetterExchange(DLX).build();
    }

    @Bean
    public Binding maintenanceAircraftBinding(Queue maintenanceAircraftQueue,
                                               TopicExchange aircraftEventsExchange) {
        return BindingBuilder.bind(maintenanceAircraftQueue).to(aircraftEventsExchange).with("aircraft.#");
    }
}
