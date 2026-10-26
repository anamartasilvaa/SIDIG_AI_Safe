package pt.isep.psoft.aisafe.maintenance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(HttpClients.custom()
                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                        .build())
                .build());
        RestTemplate template = new RestTemplate(factory);
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            request.getHeaders().set(ApiKeyFilter.HEADER, apiKey);
            request.getHeaders().set("X-Origin-Instance", instanceId);
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                String auth = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                if (auth != null && auth.startsWith("Bearer ")) {
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, auth);
                }
            }
            return execution.execute(request, body);
        };
        template.getInterceptors().add(interceptor);
        return template;
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
