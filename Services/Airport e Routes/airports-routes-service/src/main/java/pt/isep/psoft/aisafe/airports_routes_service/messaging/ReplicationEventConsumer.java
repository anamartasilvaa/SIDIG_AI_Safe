package pt.isep.psoft.aisafe.airports_routes_service.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.CreateRouteDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RegisterAirportDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.UpdateAirportDetailsDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.UpdateRouteDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Consumidor dos eventos publicados pelas outras instancias.
 * Aplica cada evento com os mesmos metodos de servico que a replicacao HTTP usava
 * (isReplication = true), que ja sao idempotentes e resolvem conflitos por lastModified
 * e originNodeId (last-writer-wins).
 */
@Component
@ConditionalOnProperty(name = "app.replication.transport", havingValue = "amqp")
public class ReplicationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReplicationEventConsumer.class);

    private final AirportService airportService;
    private final RouteService routeService;
    private final ObjectMapper mapper;
    private final String nodeId;

    public ReplicationEventConsumer(AirportService airportService,
                                    RouteService routeService,
                                    ObjectMapper mapper,
                                    @Value("${peer.node-id:${server.port:local}}") String nodeId) {
        this.airportService = airportService;
        this.routeService = routeService;
        this.mapper = mapper;
        this.nodeId = nodeId;
    }

    @RabbitListener(queues = "#{replicationQueue.name}")
    public void onEvent(Message message) {
        try {
            JsonNode envelope = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            String origin = text(envelope, "originNodeId");

            // O fanout entrega tambem os eventos desta propria instancia: ja estao aplicados.
            if (origin != null && origin.equals(nodeId)) {
                log.debug("Evento proprio ignorado ({})", text(envelope, "eventType"));
                return;
            }

            ReplicationEventType type = ReplicationEventType.valueOf(text(envelope, "eventType"));
            String path = text(envelope, "path");
            String id = ReplicationEventType.resourceId(path);
            String payload = text(envelope, "payload");
            Instant lastModified = parseInstant(text(envelope, "lastModified"));

            switch (type) {
                case AIRPORT_CREATED -> airportService.registerAirport(
                        mapper.readValue(payload, RegisterAirportDTO.class), true, lastModified, origin);
                case AIRPORT_DETAILS_UPDATED -> airportService.updateAirportDetails(
                        id, mapper.readValue(payload, UpdateAirportDetailsDTO.class), true, lastModified, origin);
                case AIRPORT_STATUS_CHANGED -> airportService.updateAirportStatus(
                        id, payload.replace("\"", ""), true, lastModified, origin);
                case ROUTE_CREATED -> routeService.createRoute(
                        mapper.readValue(payload, CreateRouteDTO.class), true, lastModified, origin);
                case ROUTE_UPDATED -> routeService.updateRoute(
                        id, mapper.readValue(payload, UpdateRouteDTO.class), true, lastModified, origin);
                case ROUTE_DEACTIVATED -> routeService.deactivateRoute(id, true, lastModified, origin);
            }
            log.info("Evento {} aplicado (origem {})", type, origin);
        } catch (Exception ex) {
            log.error("Falha ao processar evento: {}", ex.getMessage());
            // Depois das tentativas configuradas, a mensagem segue para a dead-letter queue.
            throw new AmqpRejectAndDontRequeueException("Evento nao processado", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
