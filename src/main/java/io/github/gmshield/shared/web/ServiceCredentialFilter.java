package io.github.gmshield.shared.web;

import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.StartupSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a {@code /api/v1} request through only with the frontend's service credential (Story 1.10):
 * exactly one {@code Authorization: Bearer <GMSHIELD_FRONTEND_CREDENTIAL>} header, compared in constant
 * time. Anything else is {@code service.unauthenticated} (401) before any controller runs. The actuator
 * and {@code /internal} keep their own rules. Neither the expected nor the presented value is ever logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ServiceCredentialFilter extends OncePerRequestFilter {

    static final String PUBLIC_API = "/api/v1";
    static final String BEARER = "Bearer ";
    static final ApiProblem UNAUTHENTICATED =
            ApiProblem.of(401, "service", "unauthenticated", "Service credential required");
    private static final Logger log = LoggerFactory.getLogger(ServiceCredentialFilter.class);

    private final byte[] expected;

    ServiceCredentialFilter(@Value("${gmshield.frontend-credential}") String credential) {
        if (!StartupSettings.frontendCredential(credential)) {
            // Names only, never a value (StartupSettings refuses this before Spring starts).
            throw new IllegalStateException(StartupSettings.REASON_INVALID + ": " + StartupSettings.FRONTEND_CREDENTIAL);
        }
        this.expected = (BEARER + credential.strip()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The servlet path is decoded and normalised by the server, as for the actuator filter.
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        boolean publicApi = path.equals(PUBLIC_API) || path.startsWith(PUBLIC_API + "/");
        if (publicApi && !presented(request)) {
            log.atInfo().addKeyValue("reason", "service.unauthenticated").log("request.refused");
            request.setAttribute(ProblemResponses.PROBLEM_ATTRIBUTE, UNAUTHENTICATED);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Exactly one Authorization header, equal to the expected one in time that does not depend on the value. */
    private boolean presented(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Authorization");
        if (values == null || !values.hasMoreElements()) {
            return false;
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null) {
            return false;
        }
        return sameBytes(expected, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Constant-time comparison: the time depends on the lengths only, never on where the bytes differ. */
    static boolean sameBytes(byte[] expected, byte[] given) {
        int difference = expected.length ^ given.length;
        for (int i = 0; i < expected.length; i++) {
            difference |= expected[i] ^ (i < given.length ? given[i] : 0);
        }
        return difference == 0;
    }
}
