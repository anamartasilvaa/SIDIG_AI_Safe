package pt.isep.psoft.aisafe.maintenance.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String ERROR_ATTRIBUTE = "jwt.error";
    private final SecretKey signingKey;
    public JwtAuthenticationFilter(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("jwt.secret must contain at least 32 bytes.");
        signingKey = Keys.hmacShaKeyFor(bytes);
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = Jwts.parser().verifyWith(signingKey).build()
                        .parseSignedClaims(header.substring(7).trim()).getPayload();
                String username = claims.get("username", String.class);
                if (username == null || username.isBlank()) username = claims.getSubject();
                if (username == null || username.isBlank()) throw new JwtException("token without username/subject");
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        username, null, authorities(claims.get("roles"))));
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
                request.setAttribute(ERROR_ATTRIBUTE, e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
    static List<SimpleGrantedAuthority> authorities(Object claim) {
        Stream<String> roles;
        if (claim instanceof Collection<?> list) roles = list.stream().map(String::valueOf);
        else if (claim instanceof String text) roles = Arrays.stream(text.split(","));
        else return List.of();
        return roles.map(String::trim).filter(r -> !r.isBlank())
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .map(SimpleGrantedAuthority::new).toList();
    }
}
