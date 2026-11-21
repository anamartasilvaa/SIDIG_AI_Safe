package pt.isep.psoft.aisafe.airports_routes_service.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import pt.isep.psoft.aisafe.airports_routes_service.application.AuditService;
import pt.isep.psoft.aisafe.airports_routes_service.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import pt.isep.psoft.aisafe.airports_routes_service.security.JwtAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorrelationIdFilter correlationIdFilter;
    private final AuditService auditService;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          CorrelationIdFilter correlationIdFilter, AuditService auditService) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.correlationIdFilter = correlationIdFilter;
        this.auditService = auditService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            auditService.recordDenied(request.getMethod(), request.getRequestURI());
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            auditService.recordDenied(request.getMethod(), request.getRequestURI());
                            response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        }))
                .authorizeHttpRequests(auth -> auth
                        // 1. Permitir os reencaminhamentos internos de erro para evitar que o 403 vire 401
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        .requestMatchers("/h2-console/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/snapshot")
                        .hasAuthority("ROLE_PEER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/replication-tasks")
                        .hasAuthority("ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/airports/**")
                        .hasAnyAuthority("ROLE_ATCC", "ROLE_BACKOFFICE", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/airports/**")
                        .hasAnyAuthority("ROLE_BACKOFFICE", "ROLE_ADMIN", "ROLE_PEER")
                        .requestMatchers(HttpMethod.PATCH, "/api/airports/**")
                        .hasAnyAuthority("ROLE_BACKOFFICE", "ROLE_ADMIN", "ROLE_PEER")
                        .requestMatchers(HttpMethod.GET, "/api/routes/**")
                        .hasAnyAuthority("ROLE_ATCC", "ROLE_BACKOFFICE", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/routes/**")
                        .hasAnyAuthority("ROLE_ATCC", "ROLE_BACKOFFICE", "ROLE_ADMIN", "ROLE_PEER")
                        .requestMatchers(HttpMethod.PATCH, "/api/routes/**")
                        .hasAnyAuthority("ROLE_ATCC", "ROLE_BACKOFFICE", "ROLE_ADMIN", "ROLE_PEER")

                        .anyRequest().authenticated()
                )
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}