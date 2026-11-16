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
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets an {@code /internal/v1} request through only with the worker's credential (Story 1.12): exactly one
 * {@code Authorization: Bearer <GMSHIELD_WORKER_CREDENTIAL>} header, compared in constant time, on every route,
 * {@code /version} included. Anything else is {@code service.unauthenticated} (401) before any controller runs.
 * The one exception is {@code /internal/v1/test-mode/**}, and only in test mode, where those routes exist (the
 * NFR-1 seed, the probe jobs). Neither the expected nor the presented value is ever logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class WorkerCredentialFilter extends OncePerRequestFilter {

    static final String INTERNAL_API = "/internal/v1";
    static final String TEST_MODE_ROUTES = INTERNAL_API + "/test-mode/";
    private static final Logger log = LoggerFactory.getLogger(WorkerCredentialFilter.class);

    private final byte[] expected;
    private final boolean testMode;

    WorkerCredentialFilter(@Value("${gmshield.worker-credential}") String credential, Environment environment) {
        if (!StartupSettings.credential(credential)) {
            // Names only, never a value (StartupSettings refuses this before Spring starts).
            throw new IllegalStateException(StartupSettings.REASON_INVALID + ": " + StartupSettings.WORKER_CREDENTIAL);
        }
        this.expected = (ServiceCredentialFilter.BEARER + credential.strip()).getBytes(StandardCharsets.UTF_8);
        this.testMode = environment.acceptsProfiles(Profiles.of(StartupSettings.TEST_MODE_PROFILE));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The servlet path is decoded and normalised by the server, as for the other filters.
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        boolean internalApi = path.equals(INTERNAL_API) || path.startsWith(INTERNAL_API + "/");
        boolean testModeRoute = testMode && path.startsWith(TEST_MODE_ROUTES);
        if (internalApi && !testModeRoute && !presented(request)) {
            log.atInfo().addKeyValue("reason", "service.unauthenticated").log("request.refused");
            request.setAttribute(ProblemResponses.PROBLEM_ATTRIBUTE, ServiceCredentialFilter.UNAUTHENTICATED);
            response.setHeader("WWW-Authenticate", "Bearer");
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
        return ServiceCredentialFilter.sameBytes(expected, value.getBytes(StandardCharsets.UTF_8));
    }
}
