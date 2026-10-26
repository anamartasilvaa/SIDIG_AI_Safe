package pt.isep.psoft.aisafe.airports_routes_service.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.MDC;

import java.io.IOException;

public class JwtClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private final PeerTokenService peerTokenService;

    public JwtClientHttpRequestInterceptor(PeerTokenService peerTokenService) {
        this.peerTokenService = peerTokenService;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if ("true".equalsIgnoreCase(request.getHeaders().getFirst("X-P2P-Sync"))) {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION,
                    "Bearer " + peerTokenService.createToken());
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String originalUser = authentication == null || authentication.getName() == null
                    ? "SYSTEM"
                    : authentication.getName();
            if (request.getHeaders().getFirst("X-Original-User") == null) {
                request.getHeaders().set("X-Original-User", originalUser.replace('\r', '_').replace('\n', '_'));
            }
        } else if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest currentRequest = attributes.getRequest();
            String authorization = currentRequest.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.startsWith("Bearer ")) {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
        String correlationId = MDC.get(CorrelationIdFilter.HEADER);
        if (correlationId != null && !correlationId.isBlank()) {
            request.getHeaders().set(CorrelationIdFilter.HEADER,
                    correlationId.replace('\r', '_').replace('\n', '_'));
        }

        return execution.execute(request, body);
    }
}
