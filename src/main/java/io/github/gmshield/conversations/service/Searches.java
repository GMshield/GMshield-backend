package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.SearchResultRow;
import io.github.gmshield.conversations.data.SearchRow;
import io.github.gmshield.conversations.data.SearchStore;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.scope.service.CampaignScopes;
import io.github.gmshield.scope.service.ResolvedScope;
import io.github.gmshield.scope.service.ScopeFilter;
import io.github.gmshield.scope.service.ScopedRecord;
import io.github.gmshield.scope.service.ScopedResult;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.scope.service.Tier;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The GM's searches of the library (Story 3.9). Everything here acts for the owner of the work ({@link OwnerContext}).
 *
 * <ul>
 *   <li>{@link #create}: a query of 1 to {@value Scopes#MAX_QUERY} code points, not blank (else
 *       {@code request.invalid}, nothing recorded), on a campaign of the owner (else {@code request.not_found}), with an
 *       optional tier and material that only narrow the campaign's scope ({@link ScopeFilter}). In one transaction: the
 *       search with the query encrypted (binding {@code search}/{@code query}), its filters and state {@code pending},
 *       and its {@value SearchJobs#KIND} job on the interactive lane; then the owner's searches past the newest
 *       {@value #KEPT} are pruned (their unended jobs cancelled first, then their results and rows).</li>
 *   <li>{@link #find}: the owner's search with its query decrypted, its filters, its state and code, and, when done,
 *       its stored results re-read within the campaign's current scope narrowed by its filters
 *       ({@link Scopes#results}): a result that left the scope since is dropped, and each one kept carries its
 *       material's name, section path, page, first words ({@link #firstWords}) and tier as the scope labels it now.
 *       Empty for an unknown search, another owner's, or one whose campaign is not found (deleted since).</li>
 * </ul>
 *
 * <p>Story 4.3 part B: a search may also have a record-type filter (a core key; from Story 4.4 part B any record
 * type key, {@link Scopes#recordTypeKey}, else {@code request.invalid}), which leaves only records of that type, and
 * its results may be records: a record result is re-read within the same narrowed scope ({@link Scopes#records}) and
 * carries its name (decrypted only for the answer), its type's key, its first page's place and the ids of its passages
 * there, instead of first words.
 *
 * <p>The query is never matched in SQL. Lines carry ids, counts and codes, never a query or a passage's text.
 */
@Service
public class Searches {

    static final String SEARCH = "search";
    static final String FIELD_QUERY = "query";
    /** How many searches of an owner are kept: the newest; older ones are pruned when a search is created. */
    public static final int KEPT = 20;
    /** The most results a search stores. */
    public static final int MAX_RESULTS = 20;
    /** A passage result's kind. */
    public static final String PASSAGE = "passage";
    /** A record result's kind (Story 4.3 part B). */
    public static final String RECORD = "record";
    /** The most code points of a result's first words. */
    public static final int FIRST_WORDS = 160;
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Logger log = LoggerFactory.getLogger(Searches.class);

    private final SearchStore store;
    private final Library library;
    private final Scopes scopes;
    private final CampaignScopes campaignScopes;
    private final Jobs jobs;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    Searches(SearchStore store, Library library, Scopes scopes, CampaignScopes campaignScopes, Jobs jobs, Vault vault,
             ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.store = store;
        this.library = library;
        this.scopes = scopes;
        this.campaignScopes = campaignScopes;
        this.jobs = jobs;
        this.vault = vault;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * A search's result as the GM reads it: a passage ({@code kind} {@value #PASSAGE}: its id and first words) or, from
     * Story 4.3 part B, a record ({@code kind} {@value #RECORD}: its id, name, type key and the ids of its passages on
     * its first page); both with their version, material name, section path, page and tier. The fields of the other
     * kind are null (empty for {@code passageIds}). {@code toString} names ids, the kind, the page and the tier only.
     */
    public record Result(String kind, UUID passageId, UUID recordId, UUID versionId, String materialName,
                         List<String> sectionPath, int page, String firstWords, String name, String recordType,
                         List<UUID> passageIds, Tier tier) {

        public Result {
            sectionPath = List.copyOf(sectionPath);
            passageIds = List.copyOf(passageIds);
        }

        static Result passage(ScopedResult result) {
            return new Result(PASSAGE, result.passageId(), null, result.versionId(), result.materialName(),
                    result.sectionPath(), result.page(), Searches.firstWords(result.text()), null, null, List.of(),
                    result.tier());
        }

        static Result record(ScopedRecord record) {
            return new Result(RECORD, null, record.recordId(), record.versionId(), record.materialName(),
                    record.sectionPath(), record.page(), null, record.name(), record.recordType(),
                    record.passageIds(), record.tier());
        }

        /** Whether the result is a record. */
        public boolean isRecord() {
            return RECORD.equals(kind);
        }

        @Override
        public String toString() {
            return "Result[kind=" + kind + ", passageId=" + passageId + ", recordId=" + recordId + ", versionId="
                    + versionId + ", page=" + page + ", tier=" + tier + "]";
        }
    }

    /**
     * A search of the owner: its campaign, query, filters (each null when unset; {@code recordType} from Story 4.3 part
     * B), state, code (null unless failed) and results (empty unless done). {@code toString} names ids, words and
     * counts only.
     */
    public record Found(UUID id, UUID campaignId, String query, Tier tier, UUID materialId, String recordType,
                        String state, String code, List<Result> results) {

        public Found {
            results = List.copyOf(results);
        }

        @Override
        public String toString() {
            return "Found[id=" + id + ", campaignId=" + campaignId + ", tier=" + tier + ", recordType=" + recordType
                    + ", state=" + state + ", code=" + code + ", results=" + results.size() + "]";
        }
    }

    /** Records a search of the owner's campaign with no record-type filter, as the class says; answers its id. */
    public UUID create(UUID campaignId, String query, Tier tier, UUID materialId) {
        return create(campaignId, query, tier, materialId, null);
    }

    /** Records a search of the owner's campaign, as the class says; answers its id. */
    public UUID create(UUID campaignId, String query, Tier tier, UUID materialId, String recordType) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > Scopes.MAX_QUERY
                || recordType != null && !Scopes.recordTypeKey(recordType)) {
            throw ConversationProblems.invalid();
        }
        UUID owner = OwnerContext.require();
        return writing(() -> {
            if (campaignId == null || library.campaign(campaignId).isEmpty()) {
                throw ConversationProblems.notFound();
            }
            UUID searchId = Ids.newId();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            JobRef job = jobs.create(SearchJobs.KIND, Lane.INTERACTIVE, created -> store.insertSearch(new SearchRow(
                    searchId, owner, campaignId, created.id(), vault.encryptText(query(searchId, owner), query),
                    tier == null ? null : tier.word(), materialId, recordType, SearchRow.PENDING, null, now)));
            int pruned = prune();
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                    .addKeyValue("searchId", searchId).addKeyValue("jobId", job.id())
                    .addKeyValue("tier", tier == null ? "none" : tier.word())
                    .addKeyValue("material", materialId != null)
                    .addKeyValue("recordType", Scopes.loggedType(recordType)).addKeyValue("pruned", pruned)
                    .addKeyValue("queryLength", query.codePointCount(0, query.length())).log("search.created");
            return searchId;
        });
    }

    /** The owner's searches past the newest {@value #KEPT}, removed with their results; their unended jobs cancelled. */
    private int prune() {
        List<SearchRow> older = store.searchesAfterNewest(KEPT);
        List<UUID> unended = List.copyOf(jobs.unended(older.stream().map(SearchRow::jobId).toList()));
        unended.forEach(jobs::cancel);
        older.forEach(search -> store.deleteSearch(search.id()));
        return older.size();
    }

    /** The owner's search with this id, as the class says. */
    public Optional<Found> find(UUID searchId) {
        if (searchId == null) {
            return Optional.empty();
        }
        Optional<Found> found = reading(() -> store.findSearch(searchId)
                .filter(search -> library.campaign(search.campaignId()).isPresent()).map(this::found));
        found.ifPresent(search -> log.atInfo().addKeyValue("searchId", search.id())
                .addKeyValue("state", search.state()).addKeyValue("results", search.results().size())
                .log("search.read"));
        return found;
    }

    private Found found(SearchRow search) {
        String query = vault.decryptText(query(search.id(), search.ownerId()), search.query());
        List<Result> results = List.of();
        if (SearchRow.DONE.equals(search.state())) {
            List<SearchResultRow> stored = store.results(search.id());
            results = stored.isEmpty() ? List.of() : results(search, stored);
            if (results.size() < stored.size()) {
                log.atInfo().addKeyValue("searchId", search.id()).addKeyValue("dropped", stored.size() - results.size())
                        .log("search.results_dropped");
            }
        }
        return new Found(search.id(), search.campaignId(), query, Tier.of(search.tier()).orElse(null),
                search.materialId(), search.recordType(), search.state(), search.code(), results);
    }

    /**
     * The stored results re-read within the search's current narrowed scope, in rank order: passages through
     * {@link Scopes#results}, records through {@link Scopes#records} (of the search's record type, when it has one); a
     * result that left the scope is dropped.
     */
    private List<Result> results(SearchRow search, List<SearchResultRow> stored) {
        ResolvedScope scope = scopeOf(search, false);
        List<UUID> passageIds = stored.stream().filter(row -> !row.record()).map(SearchResultRow::passageId).toList();
        List<UUID> recordIds = stored.stream().filter(SearchResultRow::record).map(SearchResultRow::recordId).toList();
        Map<UUID, Result> read = new HashMap<>();
        if (!passageIds.isEmpty()) {
            scopes.results(scope, passageIds).forEach(result -> read.put(result.passageId(), Result.passage(result)));
        }
        if (!recordIds.isEmpty()) {
            scopes.records(scope, recordIds).stream().filter(record -> search.recordType() == null
                    || search.recordType().equals(record.recordType()))
                    .forEach(record -> read.put(record.recordId(), Result.record(record)));
        }
        List<Result> results = new ArrayList<>();
        for (SearchResultRow row : stored) {
            Result result = read.get(row.record() ? row.recordId() : row.passageId());
            if (result != null) {
                results.add(result);
            }
        }
        return List.copyOf(results);
    }

    /**
     * The search's campaign's current scope, under its stored settings, narrowed by the search's filters: with its
     * pending passages for the job's input, without them for the reads that check sources and tiers only.
     */
    ResolvedScope scopeOf(SearchRow search, boolean withPending) {
        ScopeFilter filter = new ScopeFilter(Tier.of(search.tier()).orElse(null), search.materialId());
        return withPending ? scopes.resolve(search.campaignId(), campaignScopes.settings(search.campaignId()), filter)
                : scopes.resolveSources(search.campaignId(), campaignScopes.settings(search.campaignId()), filter);
    }

    /**
     * The first words of a passage's text: its runs of white space made one space, then its first {@value #FIRST_WORDS}
     * code points, cut back to the last space before them when that cut would split a word (a word longer than all of
     * them is cut where they end).
     */
    static String firstWords(String text) {
        return firstWords(text, FIRST_WORDS);
    }

    /** As {@link #firstWords(String)}, with at most {@code max} code points (Story 3.10: a conversation's name). */
    static String firstWords(String text, int max) {
        String flat = SPACES.matcher(text).replaceAll(" ").strip();
        if (flat.codePointCount(0, flat.length()) <= max) {
            return flat;
        }
        int end = flat.offsetByCodePoints(0, max);
        if (flat.charAt(end) == ' ') {
            return flat.substring(0, end);
        }
        int space = flat.lastIndexOf(' ', end - 1);
        return space > 0 ? flat.substring(0, space) : flat.substring(0, end);
    }

    static Binding query(UUID searchId, UUID owner) {
        return new Binding(SEARCH, FIELD_QUERY, searchId, owner);
    }

    private <T> T writing(Supplier<T> work) {
        return new TransactionTemplate(transactions.getObject()).execute(status -> work.get());
    }

    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}
