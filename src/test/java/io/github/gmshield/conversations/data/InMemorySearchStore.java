package io.github.gmshield.conversations.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code search} and {@code search_result} in memory (Story 3.9), for tests that run without PostgreSQL. Like
 * Hibernate's {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only; like the tables, a (search,
 * rank) and a (search, passage) are unique, the rank is 1 to 20, and a failed search has a code. A write undoes itself
 * when its transaction rolls back.
 */
public class InMemorySearchStore implements SearchStore {

    private static final Set<String> STATES = Set.of(SearchRow.PENDING, SearchRow.DONE, SearchRow.FAILED);

    public final Map<UUID, SearchRow> searches = new ConcurrentHashMap<>();
    public final Map<UUID, SearchResultRow> results = new ConcurrentHashMap<>();

    @Override
    public synchronized void insertSearch(SearchRow row) {
        ownerFor(row.ownerId());
        if (!checked(row.state(), row.code()) || searches.values().stream().anyMatch(other ->
                other.ownerId().equals(row.ownerId()) && other.jobId().equals(row.jobId()))
                || searches.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("key or check refused");
        }
        RollbackUndo.onRollback(() -> searches.remove(row.id()));
    }

    @Override
    public Optional<SearchRow> findSearch(UUID id) {
        return owned(searches).filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public Optional<SearchRow> searchOfJob(UUID jobId) {
        return owned(searches).filter(row -> row.jobId().equals(jobId)).findFirst();
    }

    @Override
    public synchronized boolean endSearch(UUID id, String state, String code) {
        Optional<SearchRow> found = findSearch(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            if (!checked(state, code)) {
                throw new IllegalStateException("check refused");
            }
            searches.put(id, before.ended(state, code));
            RollbackUndo.onRollback(() -> searches.put(id, before));
        });
        return found.isPresent();
    }

    @Override
    public synchronized void insertResults(List<SearchResultRow> rows) {
        for (SearchResultRow row : rows) {
            ownerFor(row.ownerId());
            SearchRow search = searches.get(row.searchId());
            boolean repeated = results.values().stream().anyMatch(other -> other.searchId().equals(row.searchId())
                    && (other.rank() == row.rank() || row.passageId() != null && row.passageId().equals(
                    other.passageId()) || row.recordId() != null && row.recordId().equals(other.recordId())));
            if (search == null || !search.ownerId().equals(row.ownerId()) || repeated || row.rank() < 1
                    || row.rank() > 20 || results.putIfAbsent(row.id(), row) != null) {
                throw new IllegalStateException("key, reference or check refused");
            }
            RollbackUndo.onRollback(() -> results.remove(row.id()));
        }
    }

    @Override
    public List<SearchResultRow> results(UUID searchId) {
        return owned(results).filter(row -> row.searchId().equals(searchId))
                .sorted(Comparator.comparingInt(SearchResultRow::rank)).toList();
    }

    @Override
    public List<SearchRow> searchesAfterNewest(int skip) {
        return owned(searches).sorted(Comparator.comparing(SearchRow::createdAt).thenComparing(SearchRow::id)
                .reversed()).skip(skip).toList();
    }

    @Override
    public List<SearchRow> searchesOfCampaigns(Collection<UUID> campaignIds) {
        return owned(searches).filter(row -> campaignIds.contains(row.campaignId()))
                .sorted(Comparator.comparing(SearchRow::createdAt).thenComparing(SearchRow::id)).toList();
    }

    @Override
    public synchronized boolean deleteSearch(UUID id) {
        Optional<SearchRow> found = findSearch(id);
        found.ifPresent(search -> {
            ownerFor(search.ownerId());
            for (SearchResultRow row : results(id)) {
                results.remove(row.id());
                RollbackUndo.onRollback(() -> results.put(row.id(), row));
            }
            searches.remove(id);
            RollbackUndo.onRollback(() -> searches.put(id, search));
        });
        return found.isPresent();
    }

    /** Every owner's search run by this job (tests). */
    public Optional<SearchRow> anySearchOfJob(UUID jobId) {
        return searches.values().stream().filter(row -> row.jobId().equals(jobId)).findFirst();
    }

    private static boolean checked(String state, String code) {
        return STATES.contains(state) && SearchRow.FAILED.equals(state) == (code != null);
    }

    private static <T> Stream<T> owned(Map<UUID, T> rows) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> owner.equals(ownerOf(row)));
    }

    private static UUID ownerOf(Object row) {
        return switch (row) {
            case SearchRow search -> search.ownerId();
            case SearchResultRow result -> result.ownerId();
            default -> throw new IllegalArgumentException("row");
        };
    }

    /** The current owner, who must be the row's; a write outside a transaction is refused, as JPA refuses it. */
    private static void ownerFor(UUID rowOwner) {
        if (!OwnerContext.require().equals(rowOwner)) {
            throw new IllegalStateException("another owner's row");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("no transaction");
        }
    }
}
