package io.github.gmshield.jobs.service;

import io.github.gmshield.shared.service.TraceContext;
import org.slf4j.MDC;

/**
 * Logs a job's events under the trace id of the request that created it, wherever they happen (a worker's request,
 * the sweep), so that every line about one job carries one trace id. The previous trace id comes back afterwards.
 */
final class JobTrace {

    private JobTrace() {
    }

    static void run(String traceId, Runnable work) {
        call(traceId, () -> {
            work.run();
            return null;
        });
    }

    static <T> T call(String traceId, java.util.function.Supplier<T> work) {
        String previous = MDC.get(TraceContext.KEY);
        TraceContext.set(traceId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                TraceContext.clear();
            } else {
                TraceContext.set(previous);
            }
        }
    }
}
