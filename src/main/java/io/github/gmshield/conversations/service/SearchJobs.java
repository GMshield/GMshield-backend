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
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    public boolean callsModel() {
        return false;
    }

    @Override
    public JsonNode input(JobRef job) {
        SearchRow search = searchOf(job);
        String query = vault.decryptText(Searches.query(search.id(), search.ownerId()), search.query());
        return scopes.searchInput(searches.scopeOf(search, true), query);
    }

    @Override
    public List<JobPassage> passages(JobRef job, List<UUID> ids) {
        return scopes.passages(searches.scopeOf(searchOf(job), false), ids).stream().map(passage -> new JobPassage(
                passage.id(), passage.tier().word(), passage.text(), passage.sectionPath(), passage.page())).toList();
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        List<UUID> ids = ids(result);
        Optional<SearchRow> found = pending(job, "completed");
        if (found.isEmpty()) {
            return;
        }
        SearchRow search = found.get();
        List<CitedPlace> places = ids.isEmpty() ? List.of() : scopes.references(searches.scopeOf(search, false), ids);
        if (places.size() != ids.size()) {
            store.endSearch(search.id(), SearchRow.FAILED, REFUSED_CODE);
            log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("searchId", search.id())
                    .addKeyValue("ids", ids.size()).addKeyValue("refused", ids.size() - places.size())
                    .addKeyValue("reason", REFUSED_CODE).log("search.result_refused");
            return;
        }
        List<SearchResultRow> rows = new ArrayList<>();
        for (CitedPlace place : places) {
            rows.add(new SearchResultRow(Ids.newId(), search.ownerId(), search.id(), rows.size() + 1,
                    place.passageId(), place.versionId(), place.tier().word()));
        }
        if (!rows.isEmpty()) {
            store.insertResults(rows);
        }
        store.endSearch(search.id(), SearchRow.DONE, null);
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("searchId", search.id())
                .addKeyValue("results", rows.size()).log("search.done");
    }

    @Override
    public void failed(JobRef job, String code) {
        pending(job, "failed").ifPresent(search -> {
            store.endSearch(search.id(), SearchRow.FAILED, code);
            log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("searchId", search.id()).addKeyValue("code", code)
                    .log("search.failed");
        });
    }

    /** {@code result.ids}: 0 to {@value Searches#MAX_RESULTS} distinct passage ids; {@link JobResultInvalid} else. */
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
