package pt.isep.psoft.aisafe.maintenance.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-API-Key";
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private final byte[] expectedKey;

    public ApiKeyFilter(@Value("${app.security.api-key}") String apiKey) {
        this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        String origin = request.getHeader("X-Origin-Instance");
        boolean valid = provided != null && MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            audit.warn("DENIED {} {} from={} remote={}", request.getMethod(), request.getRequestURI(), origin, request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key");
            return;
        }
        audit.info("ALLOWED {} {} from={}", request.getMethod(), request.getRequestURI(), origin);
        chain.doFilter(request, response);
    }
}
