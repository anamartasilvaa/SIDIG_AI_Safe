package pt.isep.psoft.aisafe.flight_operations.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private AuditLog() {}

    public static void record(String action, String targetId) {
        audit.info("User: {} - Action: {} - Target ID: {}", currentUser(), action, targetId);
    }

    public static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "SYSTEM" : auth.getName();
    }
}
