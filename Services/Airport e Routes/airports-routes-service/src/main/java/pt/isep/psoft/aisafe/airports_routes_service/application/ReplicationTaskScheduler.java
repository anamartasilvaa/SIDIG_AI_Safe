package pt.isep.psoft.aisafe.airports_routes_service.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import pt.isep.psoft.aisafe.airports_routes_service.domain.ReplicationTask;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ReplicationTaskRepository;
import pt.isep.psoft.aisafe.airports_routes_service.security.PeerTokenService;

import java.time.Instant;

@Component
public class ReplicationTaskScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ReplicationTaskScheduler.class);

    private final ReplicationTaskRepository repository;
    private final RestTemplate restTemplate;
    private final PeerTokenService peerTokenService;

    public ReplicationTaskScheduler(
            ReplicationTaskRepository repository,
            RestTemplate restTemplate,
            PeerTokenService peerTokenService) {
        this.repository = repository;
        this.restTemplate = restTemplate;
        this.peerTokenService = peerTokenService;
    }

    @Scheduled(fixedDelay = 5000)
    public void sendPendingTasks() {
        for (ReplicationTask task :
                repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
                        ReplicationTask.Status.PENDING,
                        Instant.now())) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(peerTokenService.createToken());
                headers.set("X-P2P-Sync", "true");

                if (task.getOriginalUser() != null) {
                    headers.set("X-Original-User", task.getOriginalUser());
                }

                if (task.getLastModified() != null) {
                    headers.set(
                            "X-Last-Modified",
                            task.getLastModified().toString());
                }

                if (task.getOriginNodeId() != null) {
                    headers.set("X-Origin-Node", task.getOriginNodeId());
                }

                HttpMethod method =
                        HttpMethod.valueOf(task.getHttpMethod());

                String url = task.getPeerUrl() + task.getPath();

                ResponseEntity<Void> response = restTemplate.exchange(
                        url,
                        method,
                        new HttpEntity<>(task.getPayload(), headers),
                        Void.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    task.markDone();
                    log.info("Tarefa {} replicada com sucesso para {}",
                            task.getId(), url);
                } else {
                    task.fail(Instant.now().plusSeconds(
                            backoff(task.getAttempts())));
                    log.warn("Resposta HTTP {} na replicação para {}",
                            response.getStatusCode(), url);
                }

            } catch (HttpClientErrorException.Conflict ex) {
                if ("PATCH".equalsIgnoreCase(task.getHttpMethod())
                        && task.getPath().endsWith("/deactivate")) {
                    task.markDone();
                } else {
                    task.fail(Instant.now().plusSeconds(
                            backoff(task.getAttempts())));
                    log.warn("Conflito na tarefa {}: {}",
                            task.getId(), ex.getMessage());
                }

            } catch (HttpClientErrorException ex) {
                task.fail(Instant.now().plusSeconds(
                        backoff(task.getAttempts())));
                log.error(
                        "Erro HTTP {} na tarefa {} para {}{}: {}",
                        ex.getStatusCode(),
                        task.getId(),
                        task.getPeerUrl(),
                        task.getPath(),
                        ex.getResponseBodyAsString());

            } catch (RestClientException | IllegalArgumentException ex) {
                task.fail(Instant.now().plusSeconds(
                        backoff(task.getAttempts())));
                log.error(
                        "Falha na tarefa {} para {}{}",
                        task.getId(),
                        task.getPeerUrl(),
                        task.getPath(),
                        ex);
            }

            if (task.getStatus() == ReplicationTask.Status.FAILED) {
                log.error(
                        "Tarefa {} falhou definitivamente após {} tentativas",
                        task.getId(),
                        task.getAttempts());
            }

            repository.save(task);
        }
    }

    private long backoff(int attempts) {
        return Math.min(60L, 5L << attempts);
    }
}