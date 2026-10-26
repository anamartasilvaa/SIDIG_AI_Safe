package pt.isep.psoft.aisafe.aircraft.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String ERROR_ATTRIBUTE = "jwt.error";

    private final JwtDecoder decoder;

    public JwtAuthenticationFilter(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Jwt jwt = decoder.decode(header.substring(7).trim());

                String username = firstNonBlank(jwt.getClaimAsString("username"),
                        jwt.getClaimAsString("preferred_username"), jwt.getSubject());
                if (username == null) {
                    throw new BadJwtException("token without username/subject");
                }
                Object roles = jwt.getClaims().get("roles");
                if (roles == null) {
                    roles = realmRoles(jwt);
                }
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(username, null, authorities(roles)));
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
                request.setAttribute(ERROR_ATTRIBUTE, e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
    private static Object realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        return realmAccess == null ? null : realmAccess.get("roles");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    static List<SimpleGrantedAuthority> authorities(Object rolesClaim) {
        Stream<String> roles;
        if (rolesClaim instanceof Collection<?> list) {
            roles = list.stream().map(String::valueOf);
        } else if (rolesClaim instanceof String text) {
            roles = Arrays.stream(text.split(","));
        } else {
            return List.of();
        }
        return roles.map(String::trim)
                .filter(r -> !r.isBlank())
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
