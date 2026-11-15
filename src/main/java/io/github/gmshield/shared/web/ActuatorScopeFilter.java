package io.github.gmshield.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets exactly one actuator path through: {@code /actuator/health}. Every other path below
 * {@code /actuator} is "not found" in the usual error shape, whatever the actuator configuration
 * says, so that a later change of that configuration cannot expose anything.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActuatorScopeFilter extends OncePerRequestFilter {

    static final String ACTUATOR = "/actuator";
    static final String HEALTH = "/actuator/health";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The servlet path is decoded and normalised by the server: "/actuator/health/../env" arrives as "/actuator/env".
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        boolean actuator = path.equals(ACTUATOR) || path.startsWith(ACTUATOR + "/");
        if (actuator && !path.equals(HEALTH)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
