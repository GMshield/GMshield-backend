package io.github.gmshield.scope.service;

/**
 * How many passages of a campaign's scope are still waiting for search: the distinct passages of the in-scope versions
 * covered by an index change that has not ended; {@code remaining >= 1}. Counts only.
 */
public record Indexing(int remaining) {

    public Indexing {
        if (remaining < 1) {
            throw new IllegalArgumentException("scope.indexing_invalid");
        }
    }
}
