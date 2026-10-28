package pt.isep.psoft.aisafe.airports_routes_service.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(properties = {
        "JWT_SECRET=0XBmn80sWuBF+EA5agPTIvn2OSePDUdEWGbQTh4Mlls=",
        "jwt.secret=0XBmn80sWuBF+EA5agPTIvn2OSePDUdEWGbQTh4Mlls=",
        "KEYSTORE_PASSWORD=password",
        "KC_DB_PASSWORD=changeit",
        "API_KEY=test-api-key"
})
class KeycloakAuthenticationIntegrationTest {

    private final String KEYCLOAK_TOKEN_URL = "http://localhost:8180/realms/aisafe/protocol/openid-connect/token";
    private final String BASE_API_URL = "http://localhost:8091/api";

    @Test
    void shouldSuccessfullyAuthenticateWithCorrectPasswordForAdmin() {
        boolean success = authenticateUser("admin.user", "admin123");
        assertTrue(success, "A autenticação com a password correta para o admin falhou!");
    }

    @Test
    void shouldSuccessfullyAuthenticateWithCorrectPasswordForAtcc() {
        boolean success = authenticateUser("atcc.user", "atcc123");
        assertTrue(success, "A autenticação com a password correta para o atcc falhou!");
    }

    @Test
    void shouldFailAuthenticationWithIncorrectPassword() {
        boolean success = authenticateUser("atcc.user", "passwordErrada123");
        assertFalse(success, "O Keycloak aceitou uma password incorreta, o que é uma falha de segurança!");
    }

    @Test
    void shouldDenyAccessWhenAtccTriesAdminEndpoint() {
        // 1. Obter o token JWT válido para o utilizador ATCC (que tem permissões limitadas)
        String atccToken = getAccessToken("atcc.user", "atcc123");
        assertNotNull(atccToken, "Deveria ter obtido um token JWT para o atcc.user");

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(atccToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>("{}", headers);

        try {
            // Tentativa de aceder a um endpoint restrito
            restTemplate.exchange(
                    BASE_API_URL + "/airports/import", // Endpoint alternativo de gestão
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            fail("O sistema permitiu que um utilizador ATCC acedesse a um endpoint restrito!");
        } catch (HttpClientErrorException e) {
            assertTrue(
                    e.getStatusCode() == HttpStatus.FORBIDDEN || e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.BAD_REQUEST,
                    "Esperava-se um bloqueio de segurança, mas obteve-se: " + e.getStatusCode()
            );
        }
    }

    private boolean authenticateUser(String username, String password) {
        String token = getAccessToken(username, password);
        return token != null;
    }

    private String getAccessToken(String username, String password) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id", "aisafe-client");
        body.add("username", username);
        body.add("password", password);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(KEYCLOAK_TOKEN_URL, request, Map.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return (String) response.getBody().get("access_token");
            }
        } catch (HttpClientErrorException e) {
            System.err.println("Erro do Keycloak (" + e.getStatusCode() + "): " + e.getResponseBodyAsString());
        } catch (Exception e) {
            System.err.println("Erro de ligação ao Keycloak: " + e.getMessage());
        }
        return null;
    }
}