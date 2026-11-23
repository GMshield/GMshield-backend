package io.github.gmshield.conversations.data;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code search} and {@code search_result} (Story 3.9), always within the owner of the work: every read sees that
 * owner's rows only, and a write for another owner is refused. Writes join the caller's transaction, which must exist.
 */
public interface SearchStore {

    void insertSearch(SearchRow row);

    Optional<SearchRow> findSearch(UUID id);

    /** The current owner's search run by this job. */
    Optional<SearchRow> searchOfJob(UUID jobId);

    /** Sets the owner's search's state and code; false for an unknown search or another owner's. */
    boolean endSearch(UUID id, String state, String code);

    /** Records the results of the current owner's search. */
    void insertResults(List<SearchResultRow> rows);

    /** The current owner's results of a search, by rank; empty for an unknown search or another owner's. */
    List<SearchResultRow> results(UUID searchId);

    /** The current owner's searches, newest first (by creation, then id), from the {@code skip}-th on. */
    List<SearchRow> searchesAfterNewest(int skip);

    /** The current owner's searches of these campaigns, oldest first. */
    List<SearchRow> searchesOfCampaigns(Collection<UUID> campaignIds);

    /** Deletes the current owner's search with its results; false when there is no such search. */
    boolean deleteSearch(UUID id);
}
