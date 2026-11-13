package pt.isep.psoft.aisafe.airports_routes_service.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filas desta instancia (so existem quando a replicacao e feita por AMQP).
 *
 * - Cada instancia tem a SUA fila duravel, ligada ao exchange fanout. Assim todas as instancias
 *   recebem todos os eventos e, se uma estiver parada, as mensagens esperam na fila.
 * - Mensagens que nao conseguem ser processadas depois de varias tentativas vao para a
 *   dead-letter queue desta instancia, em vez de se perderem ou de ficarem num ciclo infinito.
 */
@Configuration
@ConditionalOnProperty(name = "app.replication.transport", havingValue = "amqp")
public class AmqpReplicationConfig {

    @Bean
    public Queue replicationDeadLetterQueue(@Value("${peer.node-id:${server.port:local}}") String nodeId) {
        return QueueBuilder.durable("airports.events." + nodeId + ".dlq").build();
    }

    @Bean
    public Queue replicationQueue(@Value("${peer.node-id:${server.port:local}}") String nodeId) {
        String name = "airports.events." + nodeId;
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", "")          // exchange por omissao
                .withArgument("x-dead-letter-routing-key", name + ".dlq")
                .build();
    }

    @Bean
    public Binding replicationBinding(FanoutExchange airportsEventsExchange,
                                      @Qualifier("replicationQueue") Queue replicationQueue) {
        return BindingBuilder.bind(replicationQueue).to(airportsEventsExchange);
    }
}
