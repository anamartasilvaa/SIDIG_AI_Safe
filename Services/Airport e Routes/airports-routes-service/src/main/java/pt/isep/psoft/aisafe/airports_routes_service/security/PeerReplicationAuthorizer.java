package pt.isep.psoft.aisafe.airports_routes_service.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class PeerReplicationAuthorizer {

    public boolean isAuthorized(boolean requested) {
        if (!requested) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_PEER".equals(authority.getAuthority()));
    }
}
