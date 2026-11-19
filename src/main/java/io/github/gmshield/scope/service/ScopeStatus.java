package io.github.gmshield.scope.service;

/**
 * What the GM's screen needs to know of a campaign's scope, from one look-up of its sources: whether it has an
 * accepted source ({@code hasSources}: an allowed source; a pending passage always belongs to one), its passages still
 * waiting for search ({@code indexing}; null when none is), and those whose index change failed ({@code failed}; null
 * when none did). Counts and flags only.
 */
public record ScopeStatus(boolean hasSources, Indexing indexing, IndexingFailure failed) {
}
