package pt.isep.psoft.aisafe.flight_operations.config;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import pt.isep.psoft.aisafe.flight_operations.security.JwtAuthenticationFilter;
import pt.isep.psoft.aisafe.flight_operations.security.SecurityProblemHandler;
import pt.isep.psoft.aisafe.flight_operations.security.TokenDecoders;

import static pt.isep.psoft.aisafe.flight_operations.security.Roles.ADMIN;
import static pt.isep.psoft.aisafe.flight_operations.security.Roles.ATCC;
import static pt.isep.psoft.aisafe.flight_operations.security.Roles.BACKOFFICE;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Value("${app.security.jwt.jwk-set-uri:}") String jwkSetUri,
                                                   @Value("${app.security.jwt.issuer:}") String issuer,
                                                   @Value("${jwt.secret:change-this-development-secret-key-with-at-least-32-bytes}") String jwtSecret,
                                                   @Value("${app.security.jwt.accept-dev-tokens:true}") boolean acceptDevTokens)
            throws Exception {
        SecurityProblemHandler problems = new SecurityProblemHandler();
        JwtDecoder tokens = TokenDecoders.create(jwkSetUri, issuer, jwtSecret, acceptDevTokens);
        LoggerFactory.getLogger(SecurityConfig.class).info("JWT: Keycloak RS256 = {} (issuer {}), tokens de desenvolvimento HS256 = {}",
                jwkSetUri.isBlank() ? "DESLIGADO" : jwkSetUri, issuer.isBlank() ? "-" : issuer,
                acceptDevTokens ? "aceites" : "recusados");

        http
                .csrf(csrf -> csrf.disable())                       // API REST sem cookies de sessao
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))   // consola H2
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error", "/h2-console/**").permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/scheduled-flights").hasAnyAuthority(ATCC, ADMIN)
                        .requestMatchers(HttpMethod.PATCH, "/api/scheduled-flights/*/status").hasAnyAuthority(ATCC, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/scheduled-flights/route-utilization").hasAnyAuthority(BACKOFFICE, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/scheduled-flights/availability").hasAnyAuthority(ATCC, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/scheduled-flights/aircraft/**").hasAnyAuthority(ATCC, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/scheduled-flights/*").hasAnyAuthority(ATCC, BACKOFFICE, ADMIN)

                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(problems)          // 401
                        .accessDeniedHandler(problems))              // 403
                .addFilterBefore(new JwtAuthenticationFilter(tokens), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
