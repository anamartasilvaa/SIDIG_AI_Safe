package pt.isep.psoft.aisafe.flight_operations.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import pt.isep.psoft.aisafe.flight_operations.application.BusinessRuleException;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.DependencyUnavailableException;

import java.time.format.DateTimeParseException;

/** PASSO 7 - Erros com o codigo HTTP certo em vez de 500 genericos. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
    public ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("Invalid request");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail conflict(BusinessRuleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Degradacao graciosa: se um servico de que dependemos esta todo em baixo, 503 (nao 500). */
    @ExceptionHandler(DependencyUnavailableException.class)
    public ProblemDetail unavailable(DependencyUnavailableException e) {
        log.warn(e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /** Outro servico rejeitou o nosso pedido (ex.: 401 por API key errada) -> 502. */
    @ExceptionHandler(HttpClientErrorException.class)
    public ProblemDetail badGateway(HttpClientErrorException e) {
        log.warn("Downstream rejected request: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Downstream service rejected the request: " + e.getStatusCode());
    }
}
