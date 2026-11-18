package io.github.gmshield.materials.web;

import io.github.gmshield.materials.service.UploadProblems;
import io.github.gmshield.shared.service.ApiProblem;
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
 * The tus 1.0.0 protocol edge of {@code /api/v1/uploads/**} (Story 1.13): every answer, errors included, carries
 * {@code Tus-Resumable: 1.0.0} and {@code Cache-Control: no-store}; a request without {@code Tus-Resumable: 1.0.0}
 * ends here with {@code upload.tus_version} (412) and {@code Tus-Version: 1.0.0}. It runs after the service credential
 * and before the session's owner is bound.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
class UploadProtocolFilter extends OncePerRequestFilter {

    static final String TUS_VERSION = "1.0.0";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The servlet path is decoded and normalised by the server, as for the other filters.
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return !(path.equals(UploadController.UPLOADS) || path.startsWith(UploadController.UPLOADS + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Tus-Resumable", TUS_VERSION);
        response.setHeader("Cache-Control", "no-store");
        if (!TUS_VERSION.equals(request.getHeader("Tus-Resumable"))) {
            response.setHeader("Tus-Version", TUS_VERSION);
            request.setAttribute(ApiProblem.REQUEST_ATTRIBUTE, UploadProblems.TUS_VERSION);
            response.sendError(UploadProblems.TUS_VERSION.status());
            return;
        }
        chain.doFilter(request, response);
    }
}
