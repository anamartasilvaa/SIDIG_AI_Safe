package pt.isep.psoft.aisafe.airports_routes_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import pt.isep.psoft.aisafe.airports_routes_service.security.JwtAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/h2-console/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/airports/**")
                        .hasAnyAuthority("ROLE_ATCC", "ROLE_BACKOFFICE", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/airports/**")
                        .hasAnyAuthority("ROLE_BACKOFFICE", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/airports/**")
                        .hasAnyAuthority("ROLE_BACKOFFICE", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/routes/**")
                        .hasAnyAuthority("ROLE_ATCC", "ROLE_BACKOFFICE", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/routes/**")
                        .hasAnyAuthority("ROLE_ATCC", "ROLE_BACKOFFICE", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/routes/**")
                        .hasAnyAuthority("ROLE_ATCC", "ROLE_BACKOFFICE", "ROLE_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
