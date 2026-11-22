package io.github.gmshield.accounts.web;

import io.github.gmshield.accounts.service.Account;
import io.github.gmshield.accounts.service.Sessions;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.OwnerContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The request edge of the library routes (Story 1.11): the owner of a request to {@code /api/v1/systems/**},
 * {@code /api/v1/campaigns/**}, {@code /api/v1/uploads/**} (Story 1.13), {@code /api/v1/materials/**} (Story 1.14) or
 * {@code /api/v1/image-sets/**} (Story 1.21), {@code /api/v1/conversations/**} or {@code /api/v1/turns/**} (Story 2.4)
 * or {@code /api/v1/searches/**} (Story 3.9) or {@code /api/v1/saved-answers} (Story 3.12) is the account of its
 * session ({@code X-Session-Token}), and nothing else.
 * The owner is bound for the rest of the request through {@link OwnerContext#callAs} and unbound afterwards,
 * whatever happens. Without a session the request ends here with the session's problem
 * ({@code session.required}, {@code session.renewal_required}), answered as the service-credential filter
 * answers, before any controller runs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class LibraryOwnerFilter extends OncePerRequestFilter {

    static final List<String> LIBRARY_ROUTES = List.of("/api/v1/systems", "/api/v1/campaigns", "/api/v1/uploads",
            "/api/v1/materials", "/api/v1/image-sets", "/api/v1/conversations", "/api/v1/turns",
            "/api/v1/searches", "/api/v1/saved-answers");

    private final Sessions sessions;

    LibraryOwnerFilter(Sessions sessions) {
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The servlet path is decoded and normalised by the server, as for the other filters.
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return LIBRARY_ROUTES.stream().noneMatch(route -> path.equals(route) || path.startsWith(route + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Account account;
        try {
            account = sessions.account(request.getHeader(SessionController.HEADER));
        } catch (ApiException refused) {
            request.setAttribute(ApiProblem.REQUEST_ATTRIBUTE, refused.problem());
            response.sendError(refused.problem().status());
            return;
        }
        try {
            OwnerContext.callAs(account.id(), () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException failure) {
            throw failure;
        } catch (Exception other) {
            throw new ServletException(other);
        }
    }
}
