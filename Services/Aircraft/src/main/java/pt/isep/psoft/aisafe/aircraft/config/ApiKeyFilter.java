package pt.isep.psoft.aisafe.aircraft.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final byte[] expectedKey;

    public ApiKeyFilter(@Value("${app.security.api-key}") String key) {
        this.expectedKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader("X-API-Key");
        if (supplied == null || !MessageDigest.isEqual(expectedKey, supplied.getBytes(StandardCharsets.UTF_8))) {
            audit.warn("DENIED {} {} from={}", request.getMethod(), request.getRequestURI(),
                    request.getHeader("X-Origin-Instance"));
            response.sendError(401, "Missing or invalid API key"); return;
        }
        audit.info("ALLOWED {} {} from={}", request.getMethod(), request.getRequestURI(),
                request.getHeader("X-Origin-Instance"));
        chain.doFilter(request, response);
    }
}
