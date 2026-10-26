package pt.isep.psoft.aisafe.maintenance.config;

import org.junit.jupiter.api.AfterEach;
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
    private static final String URL = "http://localhost:8071/internal/aircraft/instances/CS-TTA";
    private final RestTemplate rest = new HttpClientConfig()
            .restTemplate(Duration.ofSeconds(1), Duration.ofSeconds(1), "test-key", "maintenance-test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void forwardsBearerApiKeyAndOriginHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/maintenance/records");
        request.addHeader("Authorization", "Bearer test-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        server.expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(header("X-API-Key", "test-key"))
                .andExpect(header("X-Origin-Instance", "maintenance-test"))
                .andRespond(withSuccess());

        rest.getForObject(URL, String.class);
        server.verify();
    }
}
