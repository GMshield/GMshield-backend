package io.github.gmshield.shared.web;

import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.ProblemBody;
import io.github.gmshield.shared.service.TraceContext;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.catalina.core.StandardHost;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the failures that never reach a controller (rejected by the server itself or thrown in
 * a filter) in the same error shape. Replaces Spring Boot's default error page.
 */
@RestController
@Hidden
public class FallbackErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(FallbackErrorController.class);

    @RequestMapping("${server.error.path:${error.path:/error}}")
    ResponseEntity<ProblemBody> error(HttpServletRequest request) {
        String traceId = TraceIdFilter.traceIdOf(request);
        // Without an error status the path was requested directly: it does not exist for a client.
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 404;
        ApiProblem problem = ApiProblem.forStatus(status);
        if (problem.status() >= 500) {
            // The log formatter writes the type and the place of the cause, never its message.
            Throwable thrown = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable cause
                    ? cause : null;
            // The trace id filter has already cleared the logging context: set it for this line.
            TraceContext.set(traceId);
            try {
                log.atError()
                        .setCause(thrown)
                        .addKeyValue("code", problem.code())
                        .log("request.failed");
            } finally {
                TraceContext.clear();
            }
        }
        return ProblemResponses.of(problem, traceId, null);
    }

    /** Failures that the server answers without any dispatch get the same shape (ProblemErrorReportValve). */
    @Bean
    static TomcatContextCustomizer problemErrorReport() {
        return context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(ProblemErrorReportValve.class.getName());
            }
        };
    }
}
