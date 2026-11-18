package io.github.gmshield.audit.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One row of {@code audit_entry} as stored; {@code details} is a JSON object already checked by the service. */
public record AuditRow(UUID id, Instant at, String action, UUID accountId, String traceId, String details) {

    public AuditRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(details, "details");
    }
}
