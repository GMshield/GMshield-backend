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
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The index changes of a material version (Story 2.1, AD-10, AD-13): the job kind {@value #KIND} on the ingestion
 * lane, one job per run of at most {@value #RUN} of the version's passages by ordinal. The job row holds no content:
 * the run is an {@code index_change} row keyed by the job.
 *
 * <ul>
 *   <li>{@link #start}: in the caller's transaction, with the version locked (an accept, or the reconcile of
 *       {@link IndexReconcile}): the version's earlier set of rows goes, its recipe is cleared, and a job is created for
 *       each run, in ordinal order. A version with no passage gets none.</li>
 *   <li>{@link #input}: at the claim, for a {@code ready} or {@code superseded} version (a superseded version keeps its
 *       points), the worker gets {@code source} (the version's id), {@code owner}, the material's {@code kind} and
 *       {@code language} (null when not given), {@code tokenKey} (Base64 of {@link Vault#ownerTokenKey}, never logged
 *       or stored) and {@code passages}, the name of its input, which {@link #openInput} serves.</li>
 *   <li>{@link #openInput}: the run's passages as JSON Lines {@code {id, ordinal, page, title, text}}, decrypted for
 *       that run only; the title is the last element of the passage's section path, or empty.</li>
 *   <li>{@link #completed}: the result's {@code indexRecipe} is recorded on the row, with the version locked; when no
 *       index change of the version is left unended and every row of its set reports that same recipe, the version
 *       records it.</li>
 *   <li>{@link #cancel}: a discarded version's unended index changes are cancelled, in the caller's transaction.</li>
 * </ul>
 *
 * Nothing here logs a name, a text or the token key.
 */
@Service
public class IndexChanges implements JobHandler {

    public static final String KIND = "index.change";
    /** The name of an index change's input in the internal API. */
    public static final String PASSAGES = "passages";
    /** The most passages one index change covers. */
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

    IndexChanges(Jobs jobs, MaterialStore materials, IndexStore index, Vault vault) {
        this.jobs = jobs;
        this.materials = materials;
        this.index = index;
        this.vault = vault;
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
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", version.id())
                .addKeyValue("passages", ordinals.size()).addKeyValue("jobs", created).addKeyValue("replaced", replaced)
                .log("index.changes_created");
        return created;
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
            input.put(PASSAGES, PASSAGES);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        return input;
    }

    private Optional<VersionRow> indexed(IndexChangeRow row) {
        return materials.findVersion(row.versionId()).filter(version -> INDEXED.contains(version.state()));
    }

    @Override
    public Optional<JobInput> openInput(JobRef job, String name) {
        if (!PASSAGES.equals(name)) {
            return Optional.empty();
        }
        Optional<IndexChangeRow> row = index.findChange(job.id());
        if (row.isEmpty() || indexed(row.get()).isEmpty()) {
            return Optional.empty();
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
     * A run's passages, decrypted in the input's read transaction: a run is bounded ({@value #RUN} passages of at most
     * 16,384 code points), and the vault is not read once that transaction ended. Kept only until it is written.
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
                .addKeyValue("firstOrdinal", row.firstOrdinal()).addKeyValue("lastOrdinal", row.lastOrdinal())
                .addKeyValue("recipe", recipe).addKeyValue("versionIndexed", last && uniform).log("index.change_done");
    }

    @Override
    public void failed(JobRef job, String code) {
        // The version keeps no recipe: the next reconcile (a backend start, or a new recipe) indexes it again.
        log.atWarn().addKeyValue("jobId", job.id()).addKeyValue("code", code).log("index.change_failed");
    }
}
