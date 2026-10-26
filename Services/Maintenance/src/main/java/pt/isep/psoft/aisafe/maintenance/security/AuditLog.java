package pt.isep.psoft.aisafe.maintenance.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuditLog {
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private AuditLog() {}
    public static void record(String action, String targetId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        audit.info("User: {} - Action: {} - Target ID: {}", auth == null ? "SYSTEM" : auth.getName(), action, targetId);
    }
}
