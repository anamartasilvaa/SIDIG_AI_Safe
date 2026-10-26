package pt.isep.psoft.aisafe.airports_routes_service.application;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuditService {
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private final String instance;

    public AuditService(@Value("${server.port:unknown}") String instance) {
        this.instance = instance;
    }

    public void record(String action, Object targetId, String outcome) {
        record(action, targetId, outcome, null);
    }

    public void record(String action, Object targetId, String outcome, Boolean replication) {
        HttpServletRequest request = currentRequest();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String user = authentication == null || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null
                ? "SYSTEM" : sanitize(authentication.getName());
        String originalUser = request == null ? "" : sanitize(request.getHeader("X-Original-User"));
        if (originalUser.isBlank()) originalUser = user;
        String origin = Boolean.TRUE.equals(replication) ? "REPLICATION" : "CLIENT";
        String correlationId = sanitize(MDC.get("X-Correlation-Id"));
        if (correlationId.isBlank() && request != null) {
            correlationId = sanitize(request.getHeader("X-Correlation-Id"));
        }
        if (correlationId.isBlank()) correlationId = UUID.randomUUID().toString();
        AUDIT.info("timestamp={} instance={} user={} origin={} originalUser={} action={} targetId={} outcome={} correlationId={}",
                Instant.now(), sanitize(instance), user, origin, originalUser, sanitize(action),
                sanitize(targetId), sanitize(outcome), correlationId);
    }

    public void recordDenied(String action, Object targetId) {
        record(action, targetId, "DENIED", false);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String sanitize(Object value) {
        if (value == null) return "";
        return String.valueOf(value).replace('\r', '_').replace('\n', '_').replace(' ', '_');
    }
}
