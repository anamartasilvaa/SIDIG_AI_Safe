package pt.isep.psoft.aisafe.maintenance.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtAuthenticationFilterTest {
    @Test
    void authoritiesNormalizeRoleNames() {
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_ATCC"), new SimpleGrantedAuthority("ROLE_ADMIN")),
                JwtAuthenticationFilter.authorities(List.of("ATCC", "ROLE_ADMIN")));
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_MAINTENANCE_TECHNICIAN")),
                JwtAuthenticationFilter.authorities("MAINTENANCE_TECHNICIAN"));
    }

    @Test
    void missingRolesProduceNoAuthorities() {
        assertEquals(List.of(), JwtAuthenticationFilter.authorities(null));
    }
}
