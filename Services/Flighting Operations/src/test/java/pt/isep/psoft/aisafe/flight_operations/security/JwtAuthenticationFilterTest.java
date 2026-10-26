package pt.isep.psoft.aisafe.flight_operations.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Validacao do JWT (sem Spring): tokens do Keycloak (RS256) e de desenvolvimento (HS256). */
class JwtAuthenticationFilterTest {

    /** Como em execucao: aceita tokens do Keycloak e tokens de desenvolvimento. */
    private JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(TokenDecoders.create(TestTokens.keycloakDecoder(), TestTokens.SECRET, true));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Corre o filtro e devolve o utilizador autenticado que ficou durante o pedido (ou null). */
    private Authentication authenticate(MockHttpServletRequest request) throws Exception {
        Authentication[] seen = new Authentication[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen[0] = SecurityContextHolder.getContext().getAuthentication();
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen[0];
    }

    private static MockHttpServletRequest withAuthorization(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/scheduled-flights/x");
        if (value != null) {
            request.addHeader("Authorization", value);
        }
        return request;
    }

    private static List<String> roles(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }

    @Test
    @DisplayName("Token valido: utilizador e perfis (com prefixo ROLE_) ficam no contexto de seguranca")
    void validToken() throws Exception {
        Authentication auth = authenticate(withAuthorization(
                TestTokens.bearer(TestTokens.valid("atcc.user", List.of("ATCC")))));

        assertNotNull(auth);
        assertEquals("atcc.user", auth.getName());
        assertEquals(List.of("ROLE_ATCC"), roles(auth));
    }

    @Test
    @DisplayName("Perfis em texto separado por virgulas e com ROLE_ ja incluido tambem sao aceites")
    void rolesAsText() throws Exception {
        Authentication auth = authenticate(withAuthorization(
                TestTokens.bearer(TestTokens.valid("admin.user", "ROLE_ADMIN, ATCC"))));

        assertEquals(List.of("ROLE_ADMIN", "ROLE_ATCC"), roles(auth));
    }

    @Test
    @DisplayName("Sem header Authorization: pedido segue sem utilizador (o Spring Security responde 401)")
    void noToken() throws Exception {
        MockHttpServletRequest request = withAuthorization(null);
        assertNull(authenticate(request));
        assertNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Assinatura com outro segredo: token recusado")
    void wrongSignature() throws Exception {
        MockHttpServletRequest request = withAuthorization(
                TestTokens.bearer(TestTokens.signedWithOtherSecret("intruder", List.of("ADMIN"))));

        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Token expirado: recusado")
    void expiredToken() throws Exception {
        MockHttpServletRequest request = withAuthorization(
                TestTokens.bearer(TestTokens.expired("atcc.user", List.of("ATCC"))));

        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Token mal formado ou de outro esquema (Basic): recusado / ignorado")
    void malformedToken() throws Exception {
        MockHttpServletRequest garbage = withAuthorization("Bearer isto.nao.e.um.jwt");
        assertNull(authenticate(garbage));
        assertNotNull(garbage.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));

        assertNull(authenticate(withAuthorization("Basic dXNlcjpwYXNz")));
    }

    @Test
    @DisplayName("Token sem utilizador (sem username nem sub): recusado")
    void tokenWithoutUser() throws Exception {
        MockHttpServletRequest request = withAuthorization(TestTokens.bearer(TestTokens.valid(null, List.of("ATCC"))));
        assertNull(authenticate(request));
    }

    @Test
    @DisplayName("Segredo com menos de 32 bytes e rejeitado ao arrancar (HS256 exige chave forte)")
    void weakSecretRejected() {
        assertThrows(IllegalArgumentException.class, () -> TokenDecoders.create((JwtDecoder) null, "curto", true));
    }

    // ------------------------------------------------------------------ Keycloak (RS256)

    @Test
    @DisplayName("Token do Keycloak: perfis de realm_access.roles e utilizador de preferred_username")
    void keycloakToken() throws Exception {
        Authentication auth = authenticate(withAuthorization(
                TestTokens.bearer(TestTokens.keycloak("atcc.user", List.of("ATCC")))));

        assertNotNull(auth);
        assertEquals("atcc.user", auth.getName());
        assertEquals(List.of("ROLE_ATCC"), roles(auth));
    }

    @Test
    @DisplayName("Token RS256 assinado com outra chave (nao do Keycloak): recusado")
    void keycloakTokenWithOtherKey() throws Exception {
        MockHttpServletRequest request = withAuthorization(
                TestTokens.bearer(TestTokens.keycloakSignedWithOtherKey("intruder", List.of("ADMIN"))));

        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Token de outro realm/emissor (iss diferente): recusado")
    void keycloakTokenFromOtherIssuer() throws Exception {
        MockHttpServletRequest request = withAuthorization(TestTokens.bearer(TestTokens.keycloakWithIssuer(
                "http://localhost:8180/realms/master", "admin", List.of("ADMIN"))));

        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Tokens de desenvolvimento desligados (producao): HS256 recusado, Keycloak continua aceite")
    void devTokensDisabled() throws Exception {
        filter = new JwtAuthenticationFilter(TokenDecoders.create(TestTokens.keycloakDecoder(), TestTokens.SECRET, false));

        MockHttpServletRequest dev = withAuthorization(TestTokens.bearer(TestTokens.valid("atcc.user", List.of("ATCC"))));
        assertNull(authenticate(dev));
        assertNotNull(dev.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));

        assertNotNull(authenticate(withAuthorization(
                TestTokens.bearer(TestTokens.keycloak("atcc.user", List.of("ATCC"))))));
    }

    @Test
    @DisplayName("Sem Keycloak configurado: tokens RS256 sao recusados")
    void noIdentityProviderConfigured() throws Exception {
        filter = new JwtAuthenticationFilter(TokenDecoders.create((JwtDecoder) null, TestTokens.SECRET, true));

        MockHttpServletRequest request = withAuthorization(
                TestTokens.bearer(TestTokens.keycloak("atcc.user", List.of("ATCC"))));
        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }
}
