package pt.isep.psoft.aisafe.aircraft.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.stream.Collectors;

public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        audit.warn("DENIED 401 {} {} reason={}", request.getMethod(), request.getRequestURI(),
                request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
        write(response, 401, "Unauthorized", "Missing or invalid bearer token.", request.getRequestURI());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth == null ? "anonymous" : auth.getName();
        String roles = auth == null ? "[]" : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(",", "[", "]"));
        audit.warn("DENIED 403 {} {} user={} roles={}", request.getMethod(), request.getRequestURI(), user, roles);
        write(response, 403, "Forbidden", "Your role is not allowed to perform this operation.", request.getRequestURI());
    }

    private static void write(HttpServletResponse response, int status, String title, String detail, String instance)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"instance\":\"" + instance.replace("\"", "\\\"") + "\"}");
    }
}
