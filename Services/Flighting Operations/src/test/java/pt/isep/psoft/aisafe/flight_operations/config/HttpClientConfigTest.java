package pt.isep.psoft.aisafe.flight_operations.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpClientConfigTest {

    private static final String URL = "https://localhost:8091/api/routes/OPO-LIS";

    private final RestTemplate rest = new HttpClientConfig()
            .restTemplate(Duration.ofSeconds(1), Duration.ofSeconds(1), "test-key", "fo-test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void incomingRequestWithAuthorization(String value) {
        MockHttpServletRequest incoming = new MockHttpServletRequest("POST", "/api/scheduled-flights");
        incoming.addHeader("Authorization", value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incoming));
    }

    @Test
    @DisplayName("Reenvia o JWT do utilizador ao outro servico (+ API key e id da replica)")
    void propagatesBearerToken() {
        incomingRequestWithAuthorization("Bearer abc.def.ghi");
        server.expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(header(ApiKeyFilter.HEADER, "test-key"))
                .andExpect(header("X-Origin-Instance", "fo-test"))
                .andRespond(withSuccess());

        rest.getForObject(URL, String.class);
        server.verify();
    }

    @Test
    @DisplayName("Sem pedido de cliente (ex.: replicacao em segundo plano): so API key, sem Authorization")
    void backgroundCallsUseOnlyApiKey() {
        server.expect(requestTo(URL))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(header(ApiKeyFilter.HEADER, "test-key"))
                .andRespond(withSuccess());

        rest.getForObject(URL, String.class);
        server.verify();
    }

    @Test
    @DisplayName("Credenciais que nao sao Bearer (ex.: Basic) nao sao reenviadas")
    void doesNotForwardOtherSchemes() {
        incomingRequestWithAuthorization("Basic dXNlcjpwYXNz");
        server.expect(requestTo(URL))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess());

        rest.getForObject(URL, String.class);
        server.verify();
    }
}
