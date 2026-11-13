package io.github.gmshield;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/** Test only: handlers and a filter that fail in each way the error model must cover. Outside both APIs. */
@RestController
public class ThrowingTestController {

    public static final String SECRET_MESSAGE = "select * from accounts where secret = 'do-not-leak'";
    public static final String FILTER_FAILURE_PATH = "/test-only/filter-failure";

    @GetMapping("/test-only/unexpected")
    String unexpected() {
        throw new IllegalStateException(SECRET_MESSAGE);
    }

    @GetMapping("/test-only/known")
    String known() {
        throw new ApiException(new ApiProblem(
                409, "library", "material_locked", "Material locked", Map.of("materialId", "0195-id", "limit", 3)));
    }

    @GetMapping("/test-only/number/{value}")
    String number(@PathVariable int value) {
        return "number " + value;
    }

    /** Fails after the trace id filter and before any controller: the exception leaves the filter chain. */
    @Component
    public static class FailingFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (FILTER_FAILURE_PATH.equals(request.getServletPath())) {
                throw new IllegalStateException(SECRET_MESSAGE);
            }
            chain.doFilter(request, response);
        }
    }
}
