package pt.isep.psoft.aisafe.maintenance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import pt.isep.psoft.aisafe.maintenance.security.JwtAuthenticationFilter;
import pt.isep.psoft.aisafe.maintenance.security.SecurityProblemHandler;
import static pt.isep.psoft.aisafe.maintenance.security.Roles.*;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${jwt.secret}") String secret) throws Exception {
        SecurityProblemHandler problems = new SecurityProblemHandler();
        http.csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error", "/h2-console/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/maintenance/templates").hasAnyAuthority(SUPERVISOR, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/maintenance/records").hasAnyAuthority(TECHNICIAN, SUPERVISOR, ADMIN)
                        .requestMatchers(HttpMethod.PATCH, "/api/maintenance/records/*/complete").hasAnyAuthority(SUPERVISOR, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/maintenance/**").hasAnyAuthority(ATCC, BACKOFFICE, TECHNICIAN, SUPERVISOR, ADMIN)
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .addFilterBefore(new JwtAuthenticationFilter(secret), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
