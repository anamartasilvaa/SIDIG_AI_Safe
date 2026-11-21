package pt.isep.psoft.aisafe.airports_routes_service.bootstrapper;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportSnapshotDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RouteSnapshotDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.SnapshotDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;

import java.time.Duration;
import java.util.List;

/**
 * Arranque de uma instancia com a base de dados VAZIA (disco perdido ou instancia nova).
 * A fila so guarda os eventos publicados depois de ser criada, por isso os aeroportos e rotas que
 * ja existiam sao copiados uma vez da outra instancia (GET /internal/snapshot, token ROLE_PEER).
 * - a base so conta como vazia se nao tinha aeroportos quando o bean e criado: antes dos dados iniciais
 *   (CommandLineRunner) e antes de o consumidor AMQP comecar a ler a fila (senao os eventos que estavam
 *   na fila criavam aeroportos e a base deixava de parecer vazia);
 * - corre em segundo plano depois do arranque, por isso a instancia consome a sua fila ao mesmo tempo;
 * - cada item passa pela regra last-writer-wins, por isso um snapshot mais antigo do que um evento
 *   ja consumido nao muda nada (e os dados iniciais locais sao substituidos pelos reais);
 * - se nenhuma instancia responder, tenta outra vez (retry-interval, a duplicar ate 60 s) ate uma
 *   responder; max-attempts = 0 (por omissao) significa sem limite.
 */
@Component
public class SnapshotBootstrapper {

    static final String SNAPSHOT_PATH = "/internal/snapshot";
    private static final Logger log = LoggerFactory.getLogger(SnapshotBootstrapper.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    private static final int MAX_PARTIAL_RETRIES = 3;

    private final AirportRepository airportRepository;
    private final AirportService airportService;
    private final RouteService routeService;
    private final RestTemplate restTemplate;
    private final List<String> peerUrls;
    private final boolean enabled;
    private final Duration retryInterval;
    private final int maxAttempts;

    private volatile boolean emptyAtStartup;
    private int partialRetriesLeft = MAX_PARTIAL_RETRIES;

    public SnapshotBootstrapper(AirportRepository airportRepository,
                                AirportService airportService,
                                RouteService routeService,
                                RestTemplate restTemplate,
                                @Value("${peer.urls:}") List<String> peerUrls,
                                @Value("${app.snapshot.enabled:true}") boolean enabled,
                                @Value("${app.snapshot.retry-interval:5s}") Duration retryInterval,
                                @Value("${app.snapshot.max-attempts:0}") int maxAttempts) {
        this.airportRepository = airportRepository;
        this.airportService = airportService;
        this.routeService = routeService;
        this.restTemplate = restTemplate;
        this.peerUrls = peerUrls.stream().map(String::trim).filter(url -> !url.isBlank()).toList();
        this.enabled = enabled;
        this.retryInterval = retryInterval;
        this.maxAttempts = maxAttempts;
    }

    /** Corre ao criar o bean: antes dos dados iniciais e antes de o consumidor AMQP arrancar. */
    @PostConstruct
    void checkDatabaseAtStartup() {
        emptyAtStartup = enabled && !peerUrls.isEmpty() && airportRepository.count() == 0;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!emptyAtStartup) {
            log.info("SNAPSHOT: base de dados local ja tinha dados (ou snapshot desligado); nao e necessario.");
            return;
        }
        log.info("SNAPSHOT: base de dados vazia no arranque; a pedir uma copia a {}.", peerUrls);
        Thread worker = new Thread(this::runWithRetries, "airports-snapshot-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    void runWithRetries() {
        Duration delay = retryInterval;
        for (int attempt = 1; maxAttempts <= 0 || attempt <= maxAttempts; attempt++) {
            if (bootstrapOnce()) {
                return;
            }
            log.warn("SNAPSHOT: tentativa {} sem sucesso; nova tentativa daqui a {}.", attempt, delay);
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            delay = delay.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay.multipliedBy(2);
        }
        log.error("SNAPSHOT: desisti ao fim de {} tentativas; so os eventos novos serao recebidos.", maxAttempts);
    }

    /** @return true quando terminou (copia aplicada), false para tentar outra vez. */
    boolean bootstrapOnce() {
        for (String peer : peerUrls) {
            SnapshotDTO snapshot;
            try {
                snapshot = fetch(peer);
            } catch (RuntimeException e) { // RestClientException ou circuit breaker aberto
                log.warn("SNAPSHOT: {} nao respondeu ({}).", peer, e.getMessage());
                continue;
            }
            if (snapshot == null) {
                continue;
            }
            int failed = apply(snapshot, peer);
            // Falhas pontuais (ex.: o mesmo aeroporto a chegar ao mesmo tempo pela fila) repetem-se;
            // a regra last-writer-wins torna a repeticao inofensiva.
            return failed == 0 || partialRetriesLeft-- <= 0;
        }
        return false;
    }

    private SnapshotDTO fetch(String peer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-P2P-Sync", "true"); // o interceptor junta o token interno (ROLE_PEER)
        return restTemplate.exchange(peer + SNAPSHOT_PATH, HttpMethod.GET, new HttpEntity<>(headers), SnapshotDTO.class)
                .getBody();
    }

    /** Aeroportos primeiro (as rotas precisam deles). @return numero de itens que falharam. */
    int apply(SnapshotDTO snapshot, String peer) {
        int airports = 0, routes = 0, failed = 0;
        List<AirportSnapshotDTO> airportList = snapshot.airports() == null ? List.of() : snapshot.airports();
        List<RouteSnapshotDTO> routeList = snapshot.routes() == null ? List.of() : snapshot.routes();
        for (AirportSnapshotDTO airport : airportList) {
            try {
                if (airportService.applySnapshot(airport)) airports++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("SNAPSHOT: aeroporto {} nao aplicado ({}).", airport.iataCode(), e.getMessage());
            }
        }
        for (RouteSnapshotDTO route : routeList) {
            try {
                if (routeService.applySnapshot(route)) routes++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("SNAPSHOT: rota {} nao aplicada ({}).", route.routeId(), e.getMessage());
            }
        }
        log.info("SNAPSHOT: copia de {}: {} aeroportos e {} rotas recebidos; {} aeroportos e {} rotas aplicados; {} falhas.",
                peer, airportList.size(), routeList.size(), airports, routes, failed);
        return failed;
    }
}
