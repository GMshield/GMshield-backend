/**
 * The audit log (Story 1.10, AD-30 when Epic 9 reads it): an append-only record of security events, one
 * row of {@code audit_entry} each, with an id, the time, the action, the account id, the trace id and a
 * small JSON of safe details. Nothing here updates or deletes a row, and no token, hash, email address,
 * username or library content is ever written. Readers come with Epic 9.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit", allowedDependencies = {"shared :: service"})
package io.github.gmshield.audit;
