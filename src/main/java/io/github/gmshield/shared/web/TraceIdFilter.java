package io.github.gmshield.shared.web;

import io.github.gmshield.shared.service.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Gives every request its trace id: the one in a valid {@code traceparent} header, otherwise a new
 * one. The id is in the logging context for the whole request and on the request itself, where the
 * error answers read it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    static final String HEADER = "traceparent";
    static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    /** The trace id of a request, also after the filter has finished (error dispatch). */
    static String traceIdOf(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String traceId) {
            return traceId;
        }
        String traceId = TraceContext.newTraceId();
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        return traceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceContext.fromTraceparent(request.getHeader(HEADER)).orElseGet(TraceContext::newTraceId);
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        TraceContext.set(traceId);
        long started = System.nanoTime();
        boolean escaped = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            // The status is still the one set so far (usually 200); the server answers 500 after this.
            escaped = true;
            throw failure;
        } finally {
            // The matched route pattern, never the path or the query: those may carry content.
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String routeName = route == null ? "unmatched" : route.toString();
            // Health probes arrive every few seconds: they are logged at debug level only.
            (routeName.startsWith("/actuator") ? log.atDebug() : log.atInfo())
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("route", routeName)
                    .addKeyValue("status", escaped ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus())
                    .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .log("request.completed");
            TraceContext.clear();
        }
    }
}
