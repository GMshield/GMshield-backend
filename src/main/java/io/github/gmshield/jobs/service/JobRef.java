package io.github.gmshield.jobs.service;

import java.util.UUID;

/**
 * A job as a handler sees it: its id, its owner (also bound in the {@code OwnerContext} whenever a handler runs),
 * its kind, its lane, the attempt and the trace id of the request that created it.
 */
public record JobRef(UUID id, UUID ownerId, String kind, Lane lane, int attempt, String traceId) {
}
