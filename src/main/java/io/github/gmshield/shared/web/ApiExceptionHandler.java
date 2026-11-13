package io.github.gmshield.shared.web;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.ProblemBody;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every exception of every controller into the one error shape. Only the status and the
 * code leave the application: no exception message, class name, stack trace or SQL.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemBody> known(ApiException exception, HttpServletRequest request) {
        return ProblemResponses.of(exception.problem(), TraceIdFilter.traceIdOf(request), null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    ResponseEntity<ProblemBody> unreadable(Exception exception, HttpServletRequest request) {
        return ProblemResponses.of(ApiProblem.forStatus(400), TraceIdFilter.traceIdOf(request), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemBody> other(Exception exception, HttpServletRequest request) {
        String traceId = TraceIdFilter.traceIdOf(request);
        if (exception instanceof ErrorResponse response && !response.getStatusCode().is5xxServerError()) {
            // Spring's own verdict on a request (unknown path, wrong method, unsupported type...).
            return ProblemResponses.of(
                    ApiProblem.forStatus(response.getStatusCode().value()), traceId, response.getHeaders());
        }
        // The log formatter writes the type and the place of the cause, never its message,
        // which may quote content or SQL.
        log.atError()
                .setCause(exception)
                .addKeyValue("code", ApiProblem.unexpected().code())
                .log("request.failed");
        return ProblemResponses.of(ApiProblem.unexpected(), traceId, null);
    }
}
