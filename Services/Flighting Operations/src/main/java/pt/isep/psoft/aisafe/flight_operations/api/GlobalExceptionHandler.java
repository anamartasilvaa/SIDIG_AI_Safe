package pt.isep.psoft.aisafe.flight_operations.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import pt.isep.psoft.aisafe.flight_operations.domain.SchedulingRuleViolation;
import pt.isep.psoft.aisafe.flight_operations.infrastructure.DependencyUnavailableException;

/** Erros com o codigo HTTP certo (ProblemDetail, RFC 9457) em vez de 500 genericos. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Dados invalidos: invariantes do dominio, value objects, datas, estados desconhecidos. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("Invalid request");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON request body.");
    }

    /** Regra de negocio violada: rota inativa, aviao em manutencao, alcance, conflito, transicao invalida. */
    @ExceptionHandler(SchedulingRuleViolation.class)
    public ProblemDetail conflict(SchedulingRuleViolation e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Dois pedidos alteraram o mesmo voo ao mesmo tempo (@Version). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail concurrentUpdate(ObjectOptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The flight was modified concurrently. Reload and try again.");
    }

    /** Degradacao graciosa: falta informacao de outro servico ou de outras replicas -> 503 (nao 500). */
    @ExceptionHandler(DependencyUnavailableException.class)
    public ProblemDetail unavailable(DependencyUnavailableException e) {
        log.warn(e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /** Outro servico rejeitou o nosso pedido (ex.: 401 por API key errada) -> 502. */
    @ExceptionHandler(HttpClientErrorException.class)
    public ProblemDetail badGateway(HttpClientErrorException e) {
        log.warn("Downstream rejected request: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Downstream service rejected the request: " + e.getStatusCode().value());
    }
}
