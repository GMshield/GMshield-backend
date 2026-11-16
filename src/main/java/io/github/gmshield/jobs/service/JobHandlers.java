package io.github.gmshield.jobs.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The registered handlers by kind. Looked up on first use rather than injected, so that a handler may itself use
 * {@link Jobs} without a cycle between the beans. Two handlers of one kind, or a kind that is not a short
 * lower-case word, stop the start.
 */
@Component
class JobHandlers {

    static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    private final ObjectProvider<JobHandler> handlers;
    private volatile Map<String, JobHandler> byKind;

    JobHandlers(ObjectProvider<JobHandler> handlers) {
        this.handlers = handlers;
    }

    Optional<JobHandler> find(String kind) {
        return Optional.ofNullable(byKind().get(kind));
    }

    private Map<String, JobHandler> byKind() {
        Map<String, JobHandler> current = byKind;
        if (current == null) {
            Map<String, JobHandler> found = new HashMap<>();
            handlers.orderedStream().forEach(handler -> {
                String kind = handler.kind();
                if (kind == null || !KIND.matcher(kind).matches() || handler.lane() == null) {
                    throw new IllegalStateException("jobs.handler_invalid");
                }
                if (found.putIfAbsent(kind, handler) != null) {
                    throw new IllegalStateException("jobs.handler_duplicate: " + kind);
                }
            });
            current = Map.copyOf(found);
            byKind = current;
        }
        return current;
    }
}
