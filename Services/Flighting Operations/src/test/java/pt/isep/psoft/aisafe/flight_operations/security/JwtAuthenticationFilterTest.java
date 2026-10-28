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

class JwtAuthenticationFilterTest {

    private JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(TokenDecoders.create(TestTokens.keycloakDecoder(), TestTokens.SECRET, true));

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
    @DisplayName("Valid token: user and roles (with ROLE_ prefix) remain in the security context")
    void validToken() throws Exception {
        Authentication auth = authenticate(withAuthorization(
                TestTokens.bearer(TestTokens.valid("atcc.user", List.of("ATCC")))));

        assertNotNull(auth);
        assertEquals("atcc.user", auth.getName());
        assertEquals(List.of("ROLE_ATCC"), roles(auth));
    }

    @Test
    @DisplayName("Roles as comma-separated text and with ROLE_ already included are also accepted")
    void rolesAsText() throws Exception {
        Authentication auth = authenticate(withAuthorization(
                TestTokens.bearer(TestTokens.valid("admin.user", "ROLE_ADMIN, ATCC"))));

        assertEquals(List.of("ROLE_ADMIN", "ROLE_ATCC"), roles(auth));
    }

    @Test
    @DisplayName("No Authorization header: request continues without user (Spring Security responds 401)")
    void noToken() throws Exception {
        MockHttpServletRequest request = withAuthorization(null);
        assertNull(authenticate(request));
        assertNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Signed with another secret: token rejected")
    void wrongSignature() throws Exception {
        MockHttpServletRequest request = withAuthorization(
                TestTokens.bearer(TestTokens.signedWithOtherSecret("intruder", List.of("ADMIN"))));

        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Expired token: rejected")
    void expiredToken() throws Exception {
        MockHttpServletRequest request = withAuthorization(
                TestTokens.bearer(TestTokens.expired("atcc.user", List.of("ATCC"))));

        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Malformed token or from another scheme (Basic): rejected / ignored")
    void malformedToken() throws Exception {
        MockHttpServletRequest garbage = withAuthorization("Bearer this.is.not.a.jwt");
        assertNull(authenticate(garbage));
        assertNotNull(garbage.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));

        assertNull(authenticate(withAuthorization("Basic dXNlcjpwYXNz")));
    }

    @Test
    @DisplayName("Token without user (no username or sub): rejected")
    void tokenWithoutUser() throws Exception {
        MockHttpServletRequest request = withAuthorization(TestTokens.bearer(TestTokens.valid(null, List.of("ATCC"))));
        assertNull(authenticate(request));
    }

    @Test
    @DisplayName("Secret with less than 32 bytes is rejected on startup (HS256 requires strong key)")
    void weakSecretRejected() {
        assertThrows(IllegalArgumentException.class, () -> TokenDecoders.create((JwtDecoder) null, "short", true));
    }

    @Test
    @DisplayName("Keycloak token: roles from realm_access.roles and user from preferred_username")
    void keycloakToken() throws Exception {
        Authentication auth = authenticate(withAuthorization(
                TestTokens.bearer(TestTokens.keycloak("atcc.user", List.of("ATCC")))));

        assertNotNull(auth);
        assertEquals("atcc.user", auth.getName());
        assertEquals(List.of("ROLE_ATCC"), roles(auth));
    }

    @Test
    @DisplayName("RS256 token signed with another key (not Keycloak's): rejected")
    void keycloakTokenWithOtherKey() throws Exception {
        MockHttpServletRequest request = withAuthorization(
                TestTokens.bearer(TestTokens.keycloakSignedWithOtherKey("intruder", List.of("ADMIN"))));

        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Token from another realm/issuer (different iss): rejected")
    void keycloakTokenFromOtherIssuer() throws Exception {
        MockHttpServletRequest request = withAuthorization(TestTokens.bearer(TestTokens.keycloakWithIssuer(
                "http://localhost:8180/realms/master", "admin", List.of("ADMIN"))));

        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }

    @Test
    @DisplayName("Development tokens disabled (production): HS256 rejected, Keycloak still accepted")
    void devTokensDisabled() throws Exception {
        filter = new JwtAuthenticationFilter(TokenDecoders.create(TestTokens.keycloakDecoder(), TestTokens.SECRET, false));

        MockHttpServletRequest dev = withAuthorization(TestTokens.bearer(TestTokens.valid("atcc.user", List.of("ATCC"))));
        assertNull(authenticate(dev));
        assertNotNull(dev.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));

        assertNotNull(authenticate(withAuthorization(
                TestTokens.bearer(TestTokens.keycloak("atcc.user", List.of("ATCC"))))));
    }

    @Test
    @DisplayName("No Keycloak configured: RS256 tokens are rejected")
    void noIdentityProviderConfigured() throws Exception {
        filter = new JwtAuthenticationFilter(TokenDecoders.create((JwtDecoder) null, TestTokens.SECRET, true));

        MockHttpServletRequest request = withAuthorization(
                TestTokens.bearer(TestTokens.keycloak("atcc.user", List.of("ATCC"))));
        assertNull(authenticate(request));
        assertNotNull(request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE));
    }
}