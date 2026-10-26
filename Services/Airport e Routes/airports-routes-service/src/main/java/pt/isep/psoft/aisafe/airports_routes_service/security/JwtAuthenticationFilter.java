package pt.isep.psoft.aisafe.airports_routes_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtDecoder decoder;

    public JwtAuthenticationFilter(
            @Value("${app.security.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${app.security.jwt.issuer:}") String issuer,
            @Value("${jwt.secret}") String secret,
            @Value("${app.security.jwt.accept-dev-tokens:true}") boolean acceptDevTokens) {
        this.decoder = TokenDecoders.create(jwkSetUri, issuer, secret, acceptDevTokens);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Jwt jwt = decoder.decode(header.substring(7).trim());

            String username = firstNonBlank(jwt.getClaimAsString("username"),
                    jwt.getClaimAsString("preferred_username"), jwt.getSubject());
            if (username == null) {
                throw new BadJwtException("token without username/subject");
            }
            Object roles = jwt.getClaims().get("roles");
            if (roles == null) {
                Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
                roles = realmAccess == null ? null : realmAccess.get("roles");
            }
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(username, null, authorities(roles)));
        } catch (JwtException | IllegalArgumentException ignored) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static List<SimpleGrantedAuthority> authorities(Object rolesClaim) {
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
