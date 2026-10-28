package pt.isep.psoft.aisafe.airports_routes_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.security.JwtClientHttpRequestInterceptor;
import pt.isep.psoft.aisafe.airports_routes_service.security.PeerTokenService;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Bean
    public RestTemplate restTemplate(@Value("${peer.http.connect-timeout:2000}") int connectTimeout,
                                     @Value("${peer.http.read-timeout:3000}") int readTimeout,
                                     @Value("${server.ssl.key-store:}") String keyStoreLocation,
                                     @Value("${server.ssl.key-store-password:}") String keyStorePassword,
                                     @Value("${server.ssl.key-store-type:PKCS12}") String keyStoreType,
                                     ResourceLoader resourceLoader,
                                     PeerTokenService peerTokenService) throws Exception {

        SSLContext sslContext;

        if (keyStoreLocation != null && !keyStoreLocation.isBlank()) {
            KeyStore trustStore = KeyStore.getInstance(keyStoreType);
            Resource keyStoreResource = resourceLoader.getResource(keyStoreLocation);
            try (InputStream inputStream = keyStoreResource.getInputStream()) {
                trustStore.load(inputStream, keyStorePassword.toCharArray());
            }

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        } else {
            // Fallback caso não encontre keystore no perfil ativo
            sslContext = SSLContext.getDefault();
        }

        // Configuração do HttpClient do Java com timeouts e contexto SSL
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .sslContext(sslContext)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeout));

        RestTemplate restTemplate = new RestTemplate(requestFactory);

        if (peerTokenService != null) {
            ClientHttpRequestInterceptor jwtInterceptor = new JwtClientHttpRequestInterceptor(peerTokenService);
            restTemplate.getInterceptors().add(jwtInterceptor);
        }

        return restTemplate;
    }
}