package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobInput;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobResultInvalid;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.materials.data.IndexChangeRow;
import io.github.gmshield.materials.data.IndexStore;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The index changes of a material version (Story 2.1, AD-10, AD-13): the job kind {@value #KIND} on the ingestion
 * lane, one job per run of at most {@value #RUN} of the version's passages by ordinal, and (Story 4.2 part B) one job
 * per run of its records by first page: at most {@value #RUN} records, extended to the last record of the page where the
 * run would end, so no page is split across two runs (a record's ordinal is unique only within one step's report). The
 * job row holds no content: the run is an {@code index_change} row keyed by the job, a records row marked
 * {@code records}, with its first and last page in place of ordinals.
 *
 * <ul>
 *   <li>{@link #start}: in the caller's transaction, with the version locked (an accept, or the reconcile of
 *       {@link IndexReconcile}): the version's earlier set of rows goes, its recipe is cleared, and a job is created for
 *       each run: the passages runs in ordinal order, then the records runs in page order. A version with no passage
 *       and no record gets none.</li>
 *   <li>{@link #reconcile}: the same, except when every passages run of the version's set already reports the stated
 *       recipe: then only its records runs are cut again, so a records run that failed for good never makes the
 *       passages be indexed again.</li>
 *   <li>{@link #input}: at the claim, for a {@code ready} or {@code superseded} version (a superseded version keeps its
 *       points), the worker gets {@code source} (the version's id), {@code owner}, the material's {@code kind} and
 *       {@code language} (null when not given), {@code tokenKey} (Base64 of {@link Vault#ownerTokenKey}, never logged
 *       or stored) and the name of its input, which {@link #openInput} serves: {@code passages} for a passages run,
 *       {@code records} for a records run (exactly one of the two).</li>
 *   <li>{@link #openInput}: a passages run as JSON Lines {@code {id, ordinal, page, title, text}}, decrypted for that
 *       run only; the title is the last element of the passage's section path, or empty. A records run as JSON Lines
 *       {@code {id, page, type, name, fields: [{name, value}]}} (its first page and its core type's key), decrypted for
 *       that run only: a record's name and fields leave the backend only there.</li>
 *   <li>{@link #completed}: the result's {@code indexRecipe} is recorded on the row, with the version locked; when no
 *       index change of the version is left unended and every row of its set reports that same recipe, the version
 *       records it.</li>
 *   <li>{@link #cancel}: a discarded version's unended index changes are cancelled, in the caller's transaction.</li>
 *   <li>{@link #retryFailed} (indexing at worker start): for the {@link IndexRetrySweep}, in the caller's transaction,
 *       each of the owner's runs whose job ended {@code failed}, of a {@code ready} or {@code superseded} version, with
 *       fewer than {@link IndexChangeRow#MAX_RETRIES} retries, gets a new job for the same ordinal range, with the
 *       version locked: the row moves to the new job and counts one more retry; the failed job stays as history. A
 *       whole version is never started again here. {@link #start} begins every row at 0.</li>
 * </ul>
 *
 * A run whose job ended {@code failed} still counts as not indexed: its passages stay in the keyword fallback
 * ({@link ScopeReads#pendingPassages}) and the GM's notice reports them ({@link ScopeReads#failed}), but not as
 * indexing ({@link ScopeReads#indexing}). A {@code cancelled} run is neither. Those reads see passages runs only: until
 * Story 4.3, a records run, pending or failed, changes neither the search nor the notices. The version's recipe waits
 * for every run of its set, records runs included.
 *
 * Nothing here logs a name, a text or the token key.
 */
@Service
public class IndexChanges implements JobHandler {

    public static final String KIND = "index.change";
    /** The name of a passages run's input in the internal API. */
    public static final String PASSAGES = "passages";
    /** The name of a records run's input in the internal API (Story 4.2 part B). */
    public static final String RECORDS = "records";
    /** The most passages one index change covers, and the records a records run covers before its last page ends. */
    static final int RUN = 200;
    /** An index recipe as the worker reports it (AD-10). */
    static final Pattern RECIPE = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    static final String RECIPE_INVALID = "index.recipe_invalid";
    /** The states whose index changes still run: a superseded version keeps its points. */
    private static final Set<String> INDEXED = Set.of(Materials.READY, Materials.SUPERSEDED);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(IndexChanges.class);

    private final Jobs jobs;
    private final MaterialStore materials;
    private final IndexStore index;
    private final Vault vault;
    private final ExtractedRecords records;

    IndexChanges(Jobs jobs, MaterialStore materials, IndexStore index, Vault vault, ExtractedRecords records) {
        this.jobs = jobs;
        this.materials = materials;
        this.index = index;
        this.vault = vault;
        this.records = records;
    }

    /** A new set of index changes for the version, as the class says; answers how many jobs it created. */
    int start(VersionRow version) {
        UUID owner = OwnerContext.require();
        int replaced = index.deleteChanges(version.id());
        materials.recordIndexRecipe(version.id(), null);
        List<Integer> ordinals = materials.passageOrdinals(version.id());
        int created = 0;
        for (int from = 0; from < ordinals.size(); from += RUN) {
            int first = ordinals.get(from);
            int last = ordinals.get(Math.min(from + RUN, ordinals.size()) - 1);
            jobs.create(KIND, job -> index.insertChange(new IndexChangeRow(job.id(), owner, version.id(), first, last,
                    null)));
            created++;
        }
        List<Integer> pages = records.firstPages(version.id());
        int recordJobs = startRecords(owner, version.id(), pages);
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", version.id())
                .addKeyValue("passages", ordinals.size()).addKeyValue("records", pages.size())
                .addKeyValue("jobs", created + recordJobs).addKeyValue("recordJobs", recordJobs)
                .addKeyValue("replaced", replaced).log("index.changes_created");
        return created + recordJobs;
    }

    /**
     * The reconcile of a stale version under {@code recipe} ({@link IndexReconcile}), in the caller's transaction, with
     * the version locked and none of its index changes unended. When the version's set has passages runs and each one
     * reports {@code recipe}, only its records runs are cut again (a records run that failed for good, Story 4.2 part
     * B): its passages runs, their points and the GM's notices stay as they are, and the version's recipe still waits for
     * the new records runs; with no record to cut, the set is complete under {@code recipe}, which the version then
     * records. Otherwise the whole set is replaced ({@link #start}). Answers how many jobs it created.
     */
    int reconcile(VersionRow version, String recipe) {
        List<IndexChangeRow> passagesRuns = index.changes(version.id()).stream().filter(row -> !row.records()).toList();
        boolean passagesIndexed = !passagesRuns.isEmpty()
                && passagesRuns.stream().allMatch(row -> recipe.equals(row.recipe()));
        if (!passagesIndexed) {
            return start(version);
        }
        UUID owner = OwnerContext.require();
        int replaced = index.deleteRecordChanges(version.id());
        List<Integer> pages = records.firstPages(version.id());
        int recordJobs = startRecords(owner, version.id(), pages);
        if (recordJobs == 0) {
            materials.recordIndexRecipe(version.id(), recipe);
        }
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", version.id())
                .addKeyValue("passagesRuns", passagesRuns.size()).addKeyValue("records", pages.size())
                .addKeyValue("recordJobs", recordJobs).addKeyValue("replaced", replaced)
                .log("index.records_restarted");
        return recordJobs;
    }

    /**
     * Adds a records run of one page to the version's set (Story 4.5: a corrected record's page is indexed again), in the
     * caller's transaction, with the version locked and {@code ready}: its points keep their ids and get the new text and
     * record type. The version's recipe stays: the run reports the same one.
     */
    void startPage(VersionRow version, int page) {
        UUID owner = OwnerContext.require();
        JobRef job = jobs.create(KIND, created -> index.insertChange(new IndexChangeRow(created.id(), owner,
                version.id(), page, page, null, 0, true)));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", version.id()).addKeyValue("page", page)
                .addKeyValue("jobId", job.id()).log("index.records_page_started");
    }

    /** Creates a records run's job for each of {@link #recordRuns}, in page order; answers how many. */
    private int startRecords(UUID owner, UUID versionId, List<Integer> pages) {
        int created = 0;
        for (int[] run : recordRuns(pages)) {
            jobs.create(KIND, job -> index.insertChange(new IndexChangeRow(job.id(), owner, versionId, run[0], run[1],
                    null, 0, true)));
            created++;
        }
        return created;
    }

    /**
     * The records runs of a version whose records have these first pages, in order, as {@code {firstPage, lastPage}}:
     * each takes the next {@value #RUN} records, then every further record on the page of the last one taken, so no
     * page is split across two runs.
     */
    static List<int[]> recordRuns(List<Integer> pages) {
        List<int[]> runs = new ArrayList<>();
        int from = 0;
        while (from < pages.size()) {
            int to = Math.min(from + RUN, pages.size()) - 1;
            int last = pages.get(to);
            while (to + 1 < pages.size() && pages.get(to + 1) == last) {
                to++;
            }
            runs.add(new int[] {pages.get(from), last});
            from = to + 1;
        }
        return runs;
    }

    /** Whether an index change of the owner's version has not ended. */
    boolean pending(UUID versionId) {
        return !unended(index, jobs::unended, versionId).isEmpty();
    }

    /**
     * The owner's index changes of the version that have not ended, by first ordinal: the runs of passages whose index
     * change is still pending (Story 2.2: {@link ScopeReads#pendingPassages}). {@code unendedJobs} answers which of
     * these jobs of the owner have not ended ({@link Jobs#unended}).
     */
    static List<IndexChangeRow> unended(IndexStore index, Function<Collection<UUID>, Set<UUID>> unendedJobs,
                                        UUID versionId) {
        List<IndexChangeRow> rows = index.changes(versionId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> open = unendedJobs.apply(jobIds(rows));
        return rows.stream().filter(row -> open.contains(row.jobId())).toList();
    }

    /**
     * The owner's index changes of the version whose job ended {@code failed}, by first ordinal: runs not indexed, which
     * the retry sweep may give a new job. {@code failedJobs} answers which of these jobs of the owner failed
     * ({@link Jobs#failed}).
     */
    static List<IndexChangeRow> failed(IndexStore index, Function<Collection<UUID>, Set<UUID>> failedJobs,
                                       UUID versionId) {
        List<IndexChangeRow> rows = index.changes(versionId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> failed = failedJobs.apply(jobIds(rows));
        return rows.stream().filter(row -> failed.contains(row.jobId())).toList();
    }

    /**
     * Gives each of the owner's failed runs still to retry a new job, as the class says, in the caller's transaction;
     * answers how many.
     */
    int retryFailed() {
        UUID owner = OwnerContext.require();
        // The owner's failed index.change jobs first, then only their rows still to retry: a retried run's row has
        // moved to its new job, so a failed job kept as history matches no row.
        Set<UUID> failed = jobs.failedOfKind(KIND);
        List<IndexChangeRow> candidates = failed.isEmpty() ? List.of() : index.retryable(failed);
        int retried = 0;
        for (IndexChangeRow row : candidates) {
            // The lock orders this with an accept, a reconcile or a discard of the version; the row is read again after
            // it, since a new set may have replaced it meanwhile.
            boolean indexed = materials.lockVersion(row.versionId())
                    .filter(version -> INDEXED.contains(version.state())).isPresent();
            if (!indexed || index.findChange(row.jobId()).isEmpty()) {
                continue;
            }
            JobRef job = jobs.create(KIND, created -> index.moveChange(row.jobId(), created.id())
                    .orElseThrow(() -> new IllegalStateException("index.change_missing")));
            retried++;
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", row.versionId())
                    .addKeyValue("failedJobId", row.jobId()).addKeyValue("jobId", job.id())
                    .addKeyValue("recordsRun", row.records()).addKeyValue("firstOrdinal", row.firstOrdinal())
                    .addKeyValue("lastOrdinal", row.lastOrdinal()).addKeyValue("retries", row.retries() + 1)
                    .log("index.change_retried");
        }
        return retried;
    }

    /** Cancels the owner's unended index changes of the version, in the caller's transaction; answers how many. */
    int cancel(UUID versionId) {
        int cancelled = 0;
        for (UUID jobId : jobs.unended(jobIds(index.changes(versionId)))) {
            cancelled += jobs.cancel(jobId) ? 1 : 0;
        }
        return cancelled;
    }

    private static List<UUID> jobIds(List<IndexChangeRow> rows) {
        return rows.stream().map(IndexChangeRow::jobId).toList();
    }

    // --- the job handler

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Lane lane() {
        return Lane.INGESTION;
    }

    @Override
    public JsonNode input(JobRef job) {
        IndexChangeRow row = index.findChange(job.id()).orElseThrow(() -> new IllegalStateException("index.change_missing"));
        VersionRow version = indexed(row).orElseThrow(() -> new IllegalStateException("index.version_not_indexed"));
        MaterialRow material = materials.findMaterial(version.materialId())
                .orElseThrow(() -> new IllegalStateException("index.material_missing"));
        byte[] key = vault.ownerTokenKey(job.ownerId());
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        try {
            input.put("source", version.id().toString());
            input.put("owner", job.ownerId().toString());
            input.put("kind", material.kind());
            input.put("language", material.language());
            input.put("tokenKey", Base64.getEncoder().encodeToString(key));
            String name = inputName(row);
            input.put(name, name);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        return input;
    }

    /** The name of the row's input: {@value #RECORDS} for a records run, {@value #PASSAGES} otherwise. */
    private static String inputName(IndexChangeRow row) {
        return row.records() ? RECORDS : PASSAGES;
    }

    private Optional<VersionRow> indexed(IndexChangeRow row) {
        return materials.findVersion(row.versionId()).filter(version -> INDEXED.contains(version.state()));
    }

    @Override
    public Optional<JobInput> openInput(JobRef job, String name) {
        if (!PASSAGES.equals(name) && !RECORDS.equals(name)) {
            return Optional.empty();
        }
        Optional<IndexChangeRow> row = index.findChange(job.id());
        if (row.isEmpty() || !inputName(row.get()).equals(name) || indexed(row.get()).isEmpty()) {
            return Optional.empty();
        }
        if (row.get().records()) {
            return Optional.of(recordLines(job, row.get()));
        }
        UUID owner = job.ownerId();
        ByteArrayOutputStream lines = new ByteArrayOutputStream();
        List<PassageRow> passages = materials.passages(row.get().versionId(), row.get().firstOrdinal(),
                row.get().lastOrdinal());
        for (PassageRow passage : passages) {
            List<String> path = Passages.place(vault.decrypt(Passages.place(passage.id(), owner), passage.place()))
                    .sectionPath();
            ObjectNode line = JsonNodeFactory.instance.objectNode();
            line.put("id", passage.id().toString());
            line.put("ordinal", passage.ordinal());
            line.put("page", passage.page());
            line.put("title", path.isEmpty() ? "" : path.getLast());
            line.put("text", vault.decryptText(Passages.text(passage.id(), owner), passage.text()));
            lines.writeBytes(JSON.writeValueAsBytes(line));
            lines.write('\n');
        }
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("versionId", row.get().versionId())
                .addKeyValue("passages", passages.size()).log("index.passages_opened");
        return Optional.of(new Lines(lines.toByteArray()));
    }

    /**
     * A records run as JSON Lines {@code {id, page, type, name, fields: [{name, value}]}}, as the class says. Logs the
     * count only.
     */
    private Lines recordLines(JobRef job, IndexChangeRow row) {
        ByteArrayOutputStream lines = new ByteArrayOutputStream();
        List<ExtractedRecords.Stored> found = records.readRange(row.versionId(), row.firstOrdinal(), row.lastOrdinal());
        for (ExtractedRecords.Stored record : found) {
            ObjectNode line = JsonNodeFactory.instance.objectNode();
            line.put("id", record.id().toString());
            line.put("page", record.firstPage());
            line.put("type", record.type());
            line.put("name", record.name());
            ArrayNode fields = line.putArray("fields");
            record.fields().forEach(field -> fields.addObject().put("name", field.name()).put("value", field.value()));
            lines.writeBytes(JSON.writeValueAsBytes(line));
            lines.write('\n');
        }
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("versionId", row.versionId())
                .addKeyValue("records", found.size()).log("index.records_opened");
        return new Lines(lines.toByteArray());
    }

    /**
     * A run's passages or records, decrypted in the input's read transaction: a run is bounded ({@value #RUN}
     * passages of at most 16,384 code points; about {@value #RUN} records, each from one records step's bounded
     * answer), and the vault is not read once that transaction ended. Kept only until it is written.
     */
    private static final class Lines implements JobInput {
        private final byte[] bytes;

        Lines(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            try {
                out.write(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }

        @Override
        public String toString() {
            return "Lines[" + bytes.length + " bytes]";
        }
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        JsonNode reported = result.path("indexRecipe");
        if (!reported.isString() || !RECIPE.matcher(reported.asString()).matches()) {
            throw new JobResultInvalid(RECIPE_INVALID);
        }
        String recipe = reported.asString();
        IndexChangeRow row = index.findChange(job.id()).orElseThrow(() -> new JobResultInvalid("index.change_missing"));
        UUID versionId = row.versionId();
        if (materials.lockVersion(versionId).isEmpty()) {
            throw new JobResultInvalid("index.version_missing");
        }
        index.recordChangeRecipe(job.id(), recipe);
        List<IndexChangeRow> set = index.changes(versionId);
        boolean last = jobs.unended(jobIds(set)).isEmpty();
        boolean uniform = set.stream().allMatch(other -> other.jobId().equals(job.id())
                || recipe.equals(other.recipe()));
        if (last && uniform) {
            materials.recordIndexRecipe(versionId, recipe);
        }
        log.atInfo().addKeyValue("versionId", versionId).addKeyValue("jobId", job.id())
                .addKeyValue("recordsRun", row.records()).addKeyValue("firstOrdinal", row.firstOrdinal())
                .addKeyValue("lastOrdinal", row.lastOrdinal()).addKeyValue("recipe", recipe)
                .addKeyValue("versionIndexed", last && uniform).log("index.change_done");
    }

    @Override
    public void failed(JobRef job, String code) {
        // The version keeps no recipe. The retry sweep gives this run a new job, at most MAX_RETRIES times; after that,
        // the next reconcile (a backend start, or a new recipe) indexes the version again: only its records runs when
        // every passages run already reports that recipe (reconcile).
        log.atWarn().addKeyValue("jobId", job.id()).addKeyValue("code", code).log("index.change_failed");
    }
}
