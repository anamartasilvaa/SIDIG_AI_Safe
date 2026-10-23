package pt.isep.psoft.aisafe.airports_routes_service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import pt.isep.psoft.aisafe.airports_routes_service.application.AuditService;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
