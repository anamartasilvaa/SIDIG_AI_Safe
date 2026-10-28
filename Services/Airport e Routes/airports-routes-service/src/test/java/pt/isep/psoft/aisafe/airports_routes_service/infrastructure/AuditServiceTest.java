package pt.isep.psoft.aisafe.airports_routes_service.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import pt.isep.psoft.aisafe.airports_routes_service.application.AuditService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditServiceTest {

    @Test
    void auditDoesNotContainToken() {
        Logger logger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer secret-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try {
            new AuditService("8081").record("CREATE", "LIS", "SUCCESS");
            String message = appender.list.get(0).getFormattedMessage();
            assertFalse(message.contains("secret-token"));
            assertFalse(message.contains("Authorization"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(appender);
        }
    }

    @Test
    void auditIncludesUserRolesAndRequestDetails() {
        Logger logger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/routes/RT-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "operator",
                        null,
                        List.of(
                                new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("ROLE_ATCC")
                        )
                );

        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(authentication);
            new AuditService("8081").record("UPDATE", "RT-1", "SUCCESS");
            String message = appender.list.get(0).getFormattedMessage();

            assertTrue(message.contains("user=operator"));
            assertTrue(message.contains("roles=[ROLE_ADMIN,ROLE_ATCC]"));
            assertTrue(message.contains("method=PATCH"));
            assertTrue(message.contains("path=/api/routes/RT-1"));
            assertTrue(message.contains("action=UPDATE"));
            assertTrue(message.contains("targetId=RT-1"));
            assertTrue(message.contains("outcome=SUCCESS"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(appender);
        }
    }
}