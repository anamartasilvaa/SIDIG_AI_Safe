package pt.isep.psoft.aisafe.aircraft.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import pt.isep.psoft.aisafe.aircraft.security.JwtAuthenticationFilter;
import pt.isep.psoft.aisafe.aircraft.security.TokenDecoders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import pt.isep.psoft.aisafe.aircraft.security.SecurityProblemHandler;

import static pt.isep.psoft.aisafe.aircraft.security.Roles.*;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Value("${app.security.jwt.jwk-set-uri:}") String jwkSetUri,
                                            @Value("${app.security.jwt.issuer:}") String issuer,
                                            @Value("${jwt.secret}") String secret,
                                            @Value("${app.security.jwt.accept-dev-tokens:true}") boolean acceptDevTokens) throws Exception {
        // Tokens do Keycloak (RS256, chave publica via JWKS) e, em desenvolvimento, HS256 com o jwt.secret
        JwtDecoder tokens = TokenDecoders.create(jwkSetUri, issuer, secret, acceptDevTokens);
        SecurityProblemHandler problems = new SecurityProblemHandler();
        http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error", "/h2-console/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/aircraft-models").hasAnyAuthority(BACKOFFICE, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/aircraft-models/instances").hasAnyAuthority(ATCC, ADMIN)
                        .requestMatchers(HttpMethod.PATCH, "/api/aircraft-models/instances/*/status").hasAnyAuthority(ATCC, ADMIN)
                        .requestMatchers(HttpMethod.PATCH, "/api/aircraft-models/models/**").hasAnyAuthority(BACKOFFICE, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/aircraft-models/**").hasAnyAuthority(ATCC, BACKOFFICE, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/aircraft/**").hasAnyAuthority(ATCC, BACKOFFICE, ADMIN)
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .addFilterBefore(new JwtAuthenticationFilter(tokens), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
