package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.SearchResultRow;
import io.github.gmshield.conversations.data.SearchRow;
import io.github.gmshield.conversations.data.SearchStore;
import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobPassage;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobResultInvalid;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.scope.service.CitedPlace;
import io.github.gmshield.scope.service.ResolvedScope;
import io.github.gmshield.scope.service.ScopedRecord;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.service.Vault;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The job of kind {@value #KIND} (Story 3.9): one search of the library, on the interactive lane, calling no model.
 *
 * <ul>
 *   <li>Its input is {@link Scopes#searchInput}: the question input's shape with {@code query} in place of
 *       {@code question}, within the search's campaign's current scope narrowed by its filters
 *       ({@link Searches#scopeOf}): a tier filter empties the other tiers' sources and pending passages, a material
 *       filter keeps only that material's version. Its passages (the candidates' text, which the worker reads to rank
 *       the exact query first) are read within the same narrowed scope ({@link Scopes#passages}).</li>
 *   <li>{@code completed} takes {@code result.ids}: 0 to {@value Searches#MAX_RESULTS} distinct passage ids, in rank
 *       order; anything else is {@code job.result_invalid}. Every id is checked against the narrowed scope first
 *       ({@link Scopes#references}, which decrypts no text): when any is not a passage of an allowed source still
 *       {@code ready} (unknown, another owner's, out of scope), the whole result is refused, as
 *       {@code scope.passage_refused} refuses a passages read, and the search ends {@code failed} with that code.
 *       Otherwise the ids are stored in rank order with their version and tier, and the search ends {@code done}.</li>
 *   <li>The job's end in {@code failed} ends the search {@code failed} with the job's code. A search pruned or deleted
 *       meanwhile changes nothing.</li>
 *   <li>Story 4.3 part B (internal contract 1.18.0): the input carries the search's record-type filter as
 *       {@code recordType} when it has one, and {@code result.ids} may hold record ids next to passage ids. Each id is
 *       a record of an allowed source still {@code ready} ({@link Scopes#records}) or a passage as before; with a
 *       record-type filter, only records of that type. Anything else refuses the whole result as above. Then the
 *       records of the narrowed scope whose name equals the query ({@link Scopes#namedRecords}, an equality on a keyed
 *       fingerprint, never a text search; only records the index can already find, or that the worker returned) come
 *       first: those the worker
 *       returned in its order, then the others by name and id; then every other id in the worker's order. At most
 *       {@value Searches#MAX_RESULTS} are stored, each as a passage or a record with its version and tier.</li>
 *   <li>Story 4.5 part B: of the ids not placed as records, those of records the GM deleted
 *       ({@link Scopes#deletedRecords}, whose point the index may still hold until its removal ends) are dropped before
 *       the passages are checked, so the search completes without them.</li>
 * </ul>
 *
 * Lines carry ids, counts and codes, never the query or a passage's text.
 */
@Service
public class SearchJobs implements JobHandler {

    public static final String KIND = "library.search";
    static final String REFUSED_CODE = "scope.passage_refused";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Logger log = LoggerFactory.getLogger(SearchJobs.class);

    private final SearchStore store;
    private final Searches searches;
    private final Scopes scopes;
    private final Vault vault;

    SearchJobs(SearchStore store, Searches searches, Scopes scopes, Vault vault) {
        this.store = store;
        this.searches = searches;
        this.scopes = scopes;
        this.vault = vault;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Lane lane() {
        return Lane.INTERACTIVE;
    }

    @Override
    public boolean callsModel(JobRef job) {
        return false;
    }

    @Override
    public JsonNode input(JobRef job) {
        SearchRow search = searchOf(job);
        String query = vault.decryptText(Searches.query(search.id(), search.ownerId()), search.query());
        return scopes.searchInput(searches.scopeOf(search, true), query, search.recordType());
    }

    @Override
    public List<JobPassage> passages(JobRef job, List<UUID> ids) {
        return scopes.passages(searches.scopeOf(searchOf(job), false), ids).stream().map(passage -> new JobPassage(
                passage.id(), passage.tier().word(), passage.text(), passage.sectionPath(), passage.page())).toList();
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        List<UUID> returned = ids(result);
        Optional<SearchRow> found = pending(job, "completed");
        if (found.isEmpty()) {
            return;
        }
        SearchRow search = found.get();
        ResolvedScope scope = searches.scopeOf(search, false);
        String type = search.recordType();
        Map<UUID, ScopedRecord> records = new LinkedHashMap<>();
        if (!returned.isEmpty()) {
            scopes.records(scope, returned).forEach(record -> records.put(record.recordId(), record));
        }
        // Story 4.5 part B: of the ids not placed as records, those of records the GM deleted (whose point the index
        // still held) are dropped, not refused, before the passages are checked. Read after the records, so a delete
        // committed in between drops the record instead of failing the search.
        List<UUID> unplaced = returned.stream().filter(id -> !records.containsKey(id)).toList();
        Set<UUID> deleted = unplaced.isEmpty() ? Set.of() : scopes.deletedRecords(scope, unplaced);
        List<UUID> ids = returned.stream().filter(id -> !deleted.contains(id)).toList();
        List<UUID> passageIds = unplaced.stream().filter(id -> !deleted.contains(id)).toList();
        List<CitedPlace> places = passageIds.isEmpty() ? List.of() : scopes.references(scope, passageIds);
        long wrongType = type == null ? 0 : places.size() + records.values().stream()
                .filter(record -> !type.equals(record.recordType())).count();
        if (places.size() != passageIds.size() || wrongType > 0) {
            store.endSearch(search.id(), SearchRow.FAILED, REFUSED_CODE);
            log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("searchId", search.id())
                    .addKeyValue("ids", returned.size()).addKeyValue("refused", passageIds.size() - places.size())
                    .addKeyValue("wrongType", wrongType).addKeyValue("reason", REFUSED_CODE)
                    .log("search.result_refused");
            return;
        }
        String query = vault.decryptText(Searches.query(search.id(), search.ownerId()), search.query());
        List<ScopedRecord> named = scopes.namedRecords(scope, query, type, records.keySet());
        named.forEach(record -> records.putIfAbsent(record.recordId(), record));
        Map<UUID, CitedPlace> placeOf = new HashMap<>();
        places.forEach(place -> placeOf.put(place.passageId(), place));
        List<SearchResultRow> rows = new ArrayList<>();
        for (UUID id : ranked(ids, named)) {
            ScopedRecord record = records.get(id);
            CitedPlace place = placeOf.get(id);
            if (record != null) {
                rows.add(new SearchResultRow(Ids.newId(), search.ownerId(), search.id(), rows.size() + 1, null, id,
                        record.versionId(), record.tier().word()));
            } else if (place != null) {
                rows.add(new SearchResultRow(Ids.newId(), search.ownerId(), search.id(), rows.size() + 1, id, null,
                        place.versionId(), place.tier().word()));
            }
        }
        if (!rows.isEmpty()) {
            store.insertResults(rows);
        }
        store.endSearch(search.id(), SearchRow.DONE, null);
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("searchId", search.id())
                .addKeyValue("results", rows.size())
                .addKeyValue("records", rows.stream().filter(SearchResultRow::record).count())
                .addKeyValue("exactNames", named.size()).addKeyValue("deletedRecords", deleted.size())
                .log("search.done");
    }

    /**
     * The order the results are stored in, at most {@value Searches#MAX_RESULTS}: the exact-name records the worker
     * returned, in its order; then the other exact-name records, by name (root-locale collation) and id; then every
     * other id, in the worker's order.
     */
    static List<UUID> ranked(List<UUID> ids, List<ScopedRecord> named) {
        Set<UUID> exact = new HashSet<>();
        named.forEach(record -> exact.add(record.recordId()));
        Set<UUID> returned = new HashSet<>(ids);
        Collator collator = Collator.getInstance(Locale.ROOT);
        List<UUID> ordered = new ArrayList<>(ids.stream().filter(exact::contains).toList());
        named.stream().filter(record -> !returned.contains(record.recordId()))
                .sorted(Comparator.comparing(ScopedRecord::name, collator).thenComparing(ScopedRecord::recordId))
                .forEach(record -> ordered.add(record.recordId()));
        ids.stream().filter(id -> !exact.contains(id)).forEach(ordered::add);
        return List.copyOf(ordered.subList(0, Math.min(ordered.size(), Searches.MAX_RESULTS)));
    }

    @Override
    public void failed(JobRef job, String code) {
        pending(job, "failed").ifPresent(search -> {
            store.endSearch(search.id(), SearchRow.FAILED, code);
            log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("searchId", search.id()).addKeyValue("code", code)
                    .log("search.failed");
        });
    }

    /**
     * {@code result.ids}: 0 to {@value Searches#MAX_RESULTS} distinct ids (passages or, from Story 4.3 part B,
     * records); {@link JobResultInvalid} else.
     */
    private static List<UUID> ids(JsonNode result) {
        JsonNode ids = result == null ? null : result.get("ids");
        if (ids == null || !ids.isArray() || ids.size() > Searches.MAX_RESULTS) {
            throw new JobResultInvalid("search.result_invalid");
        }
        Set<UUID> found = new LinkedHashSet<>();
        for (JsonNode id : ids) {
            if (!id.isString() || !UUID_TEXT.matcher(id.asString()).matches()
                    || !found.add(UUID.fromString(id.asString()))) {
                throw new JobResultInvalid("search.result_invalid");
            }
        }
        return List.copyOf(found);
    }

    /** The job's search while it is pending; empty (and a line naming {@code by}) when it is gone or has ended. */
    private Optional<SearchRow> pending(JobRef job, String by) {
        Optional<SearchRow> found = store.searchOfJob(job.id()).filter(row -> SearchRow.PENDING.equals(row.state()));
        if (found.isEmpty()) {
            log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("by", by).log("search.not_pending");
        }
        return found;
    }

    private SearchRow searchOf(JobRef job) {
        return store.searchOfJob(job.id()).orElseThrow(() -> new IllegalStateException("conversations.search_missing"));
    }
}
