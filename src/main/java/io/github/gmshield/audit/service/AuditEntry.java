package io.github.gmshield.audit.service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One appended audit entry: its id, time, action ({@code area.event}, for example
 * {@code session.token_reused}), the account it concerns, the trace id of the request, and the details,
 * which hold only ids, codes, counts and flags.
 */
public record AuditEntry(UUID id, Instant at, String action, UUID accountId, String traceId, Map<String, Object> details) {

    public AuditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(traceId, "traceId");
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
