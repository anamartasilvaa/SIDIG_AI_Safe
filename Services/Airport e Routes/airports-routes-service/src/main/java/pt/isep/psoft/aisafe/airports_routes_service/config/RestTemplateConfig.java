package pt.isep.psoft.aisafe.airports_routes_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.security.JwtClientHttpRequestInterceptor;

@Configuration
public class RestTemplateConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public RestTemplate restTemplate(@Value("${peer.http.connect-timeout:2000}") int connectTimeout,
                                     @Value("${peer.http.read-timeout:3000}") int readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        ClientHttpRequestInterceptor jwtInterceptor = new JwtClientHttpRequestInterceptor();
        restTemplate.getInterceptors().add(jwtInterceptor);
        return restTemplate;
    }
}
