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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthenticationFilterTest {

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(TestTokens.SECRET);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

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
        assertThrows(IllegalArgumentException.class, () -> new JwtAuthenticationFilter("curto"));
    }
}
