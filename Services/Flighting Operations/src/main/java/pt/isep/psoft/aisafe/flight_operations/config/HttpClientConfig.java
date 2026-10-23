package pt.isep.psoft.aisafe.flight_operations.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate restTemplate(@Value("${app.http.connect-timeout}") Duration connectTimeout,
                                     @Value("${app.http.read-timeout}") Duration readTimeout,
                                     @Value("${app.security.api-key}") String apiKey,
                                     @Value("${app.instance-id}") String instanceId) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        RestTemplate restTemplate = new RestTemplate(factory);

        ClientHttpRequestInterceptor authHeaders = (request, body, execution) -> {
            request.getHeaders().set(ApiKeyFilter.HEADER, apiKey);
            request.getHeaders().set("X-Origin-Instance", instanceId);

            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                String authorization = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                if (authorization != null && authorization.startsWith("Bearer ")) {
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
                }
            }
            return execution.execute(request, body);
        };
        restTemplate.getInterceptors().add(authHeaders);

        return restTemplate;
    }

    @Bean(name = "peerExecutor", destroyMethod = "shutdownNow")
    public ExecutorService peerExecutor(@Value("${app.http.peer-threads}") int threads) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(Math.max(2, threads), runnable -> {
            Thread t = new Thread(runnable, "peer-call-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
