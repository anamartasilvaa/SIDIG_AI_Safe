package pt.isep.psoft.aisafe.airports_routes_service.messaging;

import org.springframework.amqp.core.FanoutExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia AMQP do servico Airports &amp; Routes.
 *
 * Um exchange do tipo fanout: cada evento publicado e entregue a TODAS as filas ligadas
 * (uma fila por instancia), tal como no padrao publish/subscribe dos slides da UC.
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public FanoutExchange airportsEventsExchange(
            @Value("${app.messaging.exchange:airports.events}") String exchangeName) {
        // durable = true (sobrevive a reinicios do broker), autoDelete = false
        return new FanoutExchange(exchangeName, true, false);
    }
}
