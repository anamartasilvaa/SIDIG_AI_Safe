package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;

import java.time.Instant;

public record ReplicationTaskViewDTO(
        Long id,
        String peerUrl,
        String httpMethod,
        String path,
        int attempts,
        ReplicationTask.Status status,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant lastModified,
        String originNodeId
) {
    public static ReplicationTaskViewDTO from(ReplicationTask task) {
        return new ReplicationTaskViewDTO(
                task.getId(),
                task.getPeerUrl(),
                task.getHttpMethod(),
                task.getPath(),
                task.getAttempts(),
                task.getStatus(),
                task.getNextAttemptAt(),
                task.getCreatedAt(),
                task.getLastModified(),
                task.getOriginNodeId());
    }
}
