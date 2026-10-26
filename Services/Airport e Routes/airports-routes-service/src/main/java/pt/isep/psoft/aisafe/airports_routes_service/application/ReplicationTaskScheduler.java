package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ReplicationTaskRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ReplicationTaskScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReplicationTaskScheduler.class);
    private final ReplicationTaskRepository repository; private final RestTemplate restTemplate; private final ObjectMapper mapper;
    public ReplicationTaskScheduler(ReplicationTaskRepository repository, RestTemplate restTemplate, ObjectMapper mapper) {
        this.repository=repository; this.restTemplate=restTemplate; this.mapper=mapper;
    }
    @Scheduled(fixedDelay = 5000)
    public void sendPendingTasks() {
        for (ReplicationTask task : repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
                ReplicationTask.Status.PENDING, Instant.now())) {
            try {
                HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
                h.set("X-P2P-Sync","true");
                h.set("X-Original-User", task.getOriginalUser());
                if (task.getLastModified() != null) h.set("X-Last-Modified", task.getLastModified().toString());
                if (task.getOriginNodeId() != null) h.set("X-Origin-Node", task.getOriginNodeId());
                HttpMethod method = HttpMethod.valueOf(task.getHttpMethod());
                ResponseEntity<Void> response = restTemplate.exchange(task.getPeerUrl()+task.getPath(), method,
                        new HttpEntity<>(task.getPayload(), h), Void.class);
                if (response.getStatusCode().is2xxSuccessful()) task.markDone();
                else task.fail(Instant.now().plusSeconds(backoff(task.getAttempts())));
            } catch (HttpClientErrorException.Conflict ex) {
                // AI-assisted (GitHub Copilot): deactivation is idempotent when the peer reports a conflict.
                if (task.getHttpMethod().equalsIgnoreCase("PATCH")
                        && task.getPath().endsWith("/deactivate")) {
                    task.markDone();
                } else {
                    task.fail(Instant.now().plusSeconds(backoff(task.getAttempts())));
                }
            } catch (RestClientException | IllegalArgumentException ex) {
                task.fail(Instant.now().plusSeconds(backoff(task.getAttempts())));
            }
            if (task.getStatus() == ReplicationTask.Status.FAILED) {
                log.warn("Replication task {} permanently failed after {} attempts", task.getId(), task.getAttempts());
            }
            repository.save(task);
        }
    }
    private long backoff(int attempts) {
        return Math.min(60L, 5L << attempts);
    }
}
