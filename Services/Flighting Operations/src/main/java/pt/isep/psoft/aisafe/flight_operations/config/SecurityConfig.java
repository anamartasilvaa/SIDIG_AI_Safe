package pt.isep.psoft.aisafe.flight_operations.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import pt.isep.psoft.aisafe.flight_operations.security.JwtAuthenticationFilter;
import pt.isep.psoft.aisafe.flight_operations.security.SecurityProblemHandler;

import static pt.isep.psoft.aisafe.flight_operations.security.Roles.ADMIN;
import static pt.isep.psoft.aisafe.flight_operations.security.Roles.ATCC;
import static pt.isep.psoft.aisafe.flight_operations.security.Roles.BACKOFFICE;

/**
 * Seguranca da API publica (RBAC com JWT, sem sessao).
 *
 *  Endpoint                                      Perfis                    User story
 *  POST  /api/scheduled-flights                  ATCC, ADMIN               US212
 *  PATCH /api/scheduled-flights/{id}/status      ATCC, ADMIN               ciclo de vida do voo
 *  GET   /api/scheduled-flights/aircraft/{reg}   ATCC, ADMIN               US213
 *  GET   /api/scheduled-flights/availability     ATCC, ADMIN               US205
 *  GET   /api/scheduled-flights/route-utilization BACKOFFICE, ADMIN        US229
 *  GET   /api/scheduled-flights/{id}             ATCC, BACKOFFICE, ADMIN   consulta
 *  /internal/**                                  replicas com X-API-Key (ApiKeyFilter), sem JWT
 *  /actuator/health                              aberto (monitorizacao)
 *  tudo o resto                                  negado (deny by default)
 *
 * Os utilizadores e os logins vivem na API Gateway (emite o JWT); aqui so se valida o token.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Value("${jwt.secret:change-this-development-secret-key-with-at-least-32-bytes}") String jwtSecret)
            throws Exception {
        SecurityProblemHandler problems = new SecurityProblemHandler();

        http
                .csrf(csrf -> csrf.disable())                       // API REST sem cookies de sessao
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))   // consola H2
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // entre replicas: protegido pela API key (ApiKeyFilter), nao por JWT
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
                .addFilterBefore(new JwtAuthenticationFilter(jwtSecret), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
    @Bean
    public UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
