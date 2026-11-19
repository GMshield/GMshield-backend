package io.github.gmshield.scope.service;

/**
 * The passages of a campaign's scope that could not be prepared for search: the distinct passages of the in-scope
 * versions covered by an index change whose job failed ({@code passages >= 1}), and whether a retry of one of these runs
 * remains ({@code retrying}; false once every one has used its retries). Counts and a flag only.
 */
public record IndexingFailure(int passages, boolean retrying) {

    public IndexingFailure {
        if (passages < 1) {
            throw new IllegalArgumentException("scope.indexing_failure_invalid");
        }
    }
}
