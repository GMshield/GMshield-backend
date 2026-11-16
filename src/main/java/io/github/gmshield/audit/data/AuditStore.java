package io.github.gmshield.audit.data;

/** The table {@code audit_entry}: rows are appended, never changed or removed. */
public interface AuditStore {

    void append(AuditRow row);
}
