package pt.isep.psoft.aisafe.airports_routes_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PeerResilienceInterceptorTest {

    @Test
    void testCircuitBreakerOpensOnFailures() {
        PeerResilienceInterceptor interceptor = new PeerResilienceInterceptor(
                2, Duration.ofSeconds(10), 0, Duration.ofMillis(10)
        );

        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, "https://localhost:8092/api/airports");
        byte[] body = new byte[0];

        // 1ª Falha (espera IOException lançada pela lambda)
        assertThrows(IOException.class, () ->
                interceptor.intercept(request, body, (req, b) -> {
                    throw new IOException("Connection refused");
                })
        );

        // 2ª Falha (Atinge o limiar e abre o circuito)
        assertThrows(IOException.class, () ->
                interceptor.intercept(request, body, (req, b) -> {
                    throw new IOException("Connection refused");
                })
        );

        // 3ª Tentativa deve recusar imediatamente por circuito aberto (Fail-Fast lançando RuntimeException ou IOException)
        Exception ex = assertThrows(Exception.class, () ->
                interceptor.intercept(request, body, (req, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK))
        );

        assertTrue(ex.getMessage().contains("Peer circuit is open") || ex.getMessage().contains("circuit"));
    }
}