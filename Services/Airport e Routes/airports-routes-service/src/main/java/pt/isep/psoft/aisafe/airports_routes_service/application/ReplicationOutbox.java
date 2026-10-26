package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ReplicationTaskRepository;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportViewDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.CreateRouteDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RegisterAirportDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RouteViewDTO;

@Service
public class ReplicationOutbox {
    private final ReplicationTaskRepository repository; private final ObjectMapper mapper;
    public ReplicationOutbox(ReplicationTaskRepository repository, ObjectMapper mapper){this.repository=repository;this.mapper=mapper;}
    public void enqueue(List<String> peers, String method, String path, Object payload) {
        Instant lastModified = null; String originNodeId = null;
        if (payload instanceof RegisterAirportDTO p) { lastModified=p.lastModified(); originNodeId=p.originNodeId(); }
        else if (payload instanceof CreateRouteDTO p) { lastModified=p.lastModified(); originNodeId=p.originNodeId(); }
        else if (payload instanceof AirportViewDTO p) { lastModified=p.lastModified(); originNodeId=p.originNodeId(); }
        else if (payload instanceof RouteViewDTO p) { lastModified=p.lastModified(); originNodeId=p.originNodeId(); }
        enqueue(peers, method, path, payload, lastModified, originNodeId);
    }
    public void enqueue(List<String> peers, String method, String path, Object payload,
                        Instant lastModified, String originNodeId) {
        try {
            String json = mapper.writeValueAsString(payload);
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String originalUser = authentication == null || authentication.getName() == null
                    ? "SYSTEM" : authentication.getName();
            originalUser = originalUser.replace('\r', '_').replace('\n', '_');
            for (String peer : peers) {
                repository.save(new ReplicationTask(peer, method, path, json, originalUser, lastModified, originNodeId));
            }
        } catch (JsonProcessingException e) { throw new IllegalStateException("Unable to serialize replication payload", e); }
    }
}
