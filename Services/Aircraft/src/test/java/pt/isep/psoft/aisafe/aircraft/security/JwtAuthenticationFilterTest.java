package pt.isep.psoft.aisafe.aircraft.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtAuthenticationFilterTest {
    @Test
    void authoritiesNormalizeRoleNames() {
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_ATCC"), new SimpleGrantedAuthority("ROLE_ADMIN")),
                JwtAuthenticationFilter.authorities(List.of("ATCC", "ROLE_ADMIN")));
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_BACKOFFICE")),
                JwtAuthenticationFilter.authorities("BACKOFFICE"));
    }

    @Test
    void missingRolesProduceAnonymousAuthorities() {
        assertEquals(List.of(), JwtAuthenticationFilter.authorities(null));
    }
}
