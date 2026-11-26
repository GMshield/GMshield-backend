package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactIndexRow;
import io.github.gmshield.artifacts.data.ArtifactIndexStore;
import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.ArtifactVersionStore;
import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobInput;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobResultInvalid;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.knowledge.service.ArtifactRecords;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.scope.service.ScopedRecordContent;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The index jobs of an artifact's versions (Story 5.1 part B): the job kind {@value #KIND} on the ingestion lane, one
 * job per artifact version, modelled on the materials' index changes. The job row holds no content: its input is an
 * {@code artifact_index} row keyed by the job, which names the version and records the recipe its result reported.
 *
 * <ul>
 *   <li>{@link #start}: in the caller's transaction (an artifact's save, edit or restore), a job for the version. From
 *       Story 5.2 it first cancels the unended jobs of the artifact's older versions: the worker keeps one point per
 *       artifact, so an older version's job must never finish after the newer one's and leave its text searchable.
 *       {@link #lockUnended} takes those jobs' locks before the writer locks the version, in the order a job's
 *       completion takes them (its job, then its version).</li>
 *   <li>{@link #input}: at the claim, the input of a records run of one record: {@code source} (the artifact's id),
 *       {@code owner}, {@code kind} {@value #ARTIFACT_KIND}, {@code language} null, {@code tokenKey} (Base64 of
 *       {@link Vault#ownerTokenKey}, never logged or stored) and {@code records}, the name of its input.</li>
 *   <li>{@link #openInput}: that input, one JSON line {@code {id, page: 1, type, name, fields: [{name, value}]}}, the
 *       artifact's id, its record type's key, its version's name and fields, then its content, when it has one, as a
 *       last field named {@value ScopedRecordContent#CONTENT_FIELD}: an artifact's text leaves the backend only there, as data.</li>
 *   <li>{@link #completed}: the result's {@code indexRecipe} is recorded on the row, with the version locked, and is
 *       then the version's recipe. A recipe that is not one is {@code index.recipe_invalid}.</li>
 *   <li>{@link #reconcile} (the backfill, {@link ArtifactIndexReconcile}): in the caller's transaction, as one owner,
 *       each newest version that no row reports under the stated recipe, of a campaign still readable and with no
 *       unended job, gets a new job, with the version locked (its earlier rows go). So the artifacts saved before part B
 *       are indexed the first time the worker states its recipe, and all of them again under a new one. From Story 5.2
 *       a version that is no longer the newest once locked (an edit committed meanwhile) is skipped.</li>
 *   <li>{@link #retryFailed} ({@link ArtifactIndexRetrySweep}): each of the owner's rows whose job ended
 *       {@code failed}, with fewer than {@link ArtifactIndexRow#MAX_RETRIES} retries, of its artifact's newest version
 *       (Story 5.2: an older version is never indexed again) and of a campaign still readable, gets a new job; the
 *       row moves to it and counts one more retry. A failed job stays as history.</li>
 *   <li>{@link #cancel} (a campaign's or a system's deletion): the unended jobs of these artifacts are cancelled.</li>
 * </ul>
 *
 * An artifact is found by a question only once its job has ended: the worker finds it through the index. Nothing here
 * logs a name, a field, a text or the token key.
 */
@Service
public class ArtifactIndexing implements JobHandler {

    public static final String KIND = "index.artifact";
    /** The name of the job's input in the internal API: a records run's. */
    public static final String RECORDS = "records";
    /** The {@code kind} of an artifact's point in the index. */
    public static final String ARTIFACT_KIND = "artifact";
    /** An index recipe as the worker reports it (AD-10), as the materials' index changes check it. */
    static final Pattern RECIPE = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    static final String RECIPE_INVALID = "index.recipe_invalid";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(ArtifactIndexing.class);

    private final Jobs jobs;
    private final ArtifactIndexStore index;
    private final ArtifactVersionStore versions;
    private final ArtifactRecords records;
    private final Library library;
    private final Vault vault;
    private final Clock clock;

    ArtifactIndexing(Jobs jobs, ArtifactIndexStore index, ArtifactVersionStore versions, ArtifactRecords records,
                     Library library, Vault vault, ObjectProvider<Clock> clock) {
        this.jobs = jobs;
        this.index = index;
        this.versions = versions;
        this.records = records;
        this.library = library;
        this.vault = vault;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Creates the index job of the owner's artifact version, in the caller's transaction, after cancelling the unended
     * jobs of the artifact's older versions (Story 5.2); answers its job's id.
     */
    UUID start(UUID recordId, int number, Instant at) {
        UUID owner = OwnerContext.require();
        List<UUID> older = index.ofRecords(List.of(recordId)).stream().filter(row -> row.number() < number)
                .map(ArtifactIndexRow::jobId).toList();
        int cancelled = 0;
        for (UUID jobId : jobs.unended(older).stream().sorted().toList()) {
            cancelled += jobs.cancel(jobId) ? 1 : 0;
        }
        if (cancelled > 0) {
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("artifactId", recordId)
                    .addKeyValue("version", number).addKeyValue("cancelled", cancelled)
                    .log("artifacts.index_superseded");
        }
        UUID jobId = jobs.create(KIND, job -> index.insert(new ArtifactIndexRow(job.id(), owner, recordId, number, null,
                0, at))).id();
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("artifactId", recordId).addKeyValue("version", number)
                .addKeyValue("jobId", jobId).log("artifacts.index_created");
        return jobId;
    }

    /**
     * Locks the owner's unended index jobs of the artifact {@code recordId}, every version, in one order, in the
     * caller's transaction (Story 5.2): an edit or a restore takes them before it locks the artifact's newest version,
     * as a job's completion takes its job before its version, so the two never wait on each other.
     */
    void lockUnended(UUID recordId) {
        OwnerContext.require();
        for (UUID jobId : jobs.unended(jobsOf(List.of(recordId))).stream().sorted().toList()) {
            jobs.lock(jobId);
        }
    }

    /**
     * The backfill under {@code recipe}, as the class says, in the caller's transaction as the owner; answers how many
     * versions got a new job.
     */
    int reconcile(String recipe) {
        UUID owner = OwnerContext.require();
        int started = 0;
        for (ArtifactIndexStore.VersionKey key : index.notIndexedUnder(recipe)) {
            // The lock orders this with the version's completion, a retry and another reconcile; read again after it.
            Optional<ArtifactVersionRow> locked = versions.lock(key.recordId(), key.number());
            // Story 5.2: an edit may have committed since the read; only the newest version is ever indexed.
            if (locked.isEmpty() || !newest(key.recordId(), key.number()) || !readable(key.recordId())) {
                continue;
            }
            List<ArtifactIndexRow> rows = index.ofVersion(key.recordId(), key.number());
            boolean indexed = rows.stream().anyMatch(row -> recipe.equals(row.recipe()));
            if (indexed || !jobs.unended(jobIds(rows)).isEmpty()) {
                continue;
            }
            index.deleteOfVersion(key.recordId(), key.number());
            start(key.recordId(), key.number(), now());
            started++;
        }
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("recipe", recipe).addKeyValue("started", started)
                .log("artifacts.index_reconciled");
        return started;
    }

    /** Gives each of the owner's failed rows still to retry a new job, as the class says; answers how many. */
    int retryFailed() {
        UUID owner = OwnerContext.require();
        Set<UUID> failed = jobs.failedOfKind(KIND);
        List<ArtifactIndexRow> candidates = failed.isEmpty() ? List.of() : index.retryable(failed);
        int retried = 0;
        for (ArtifactIndexRow row : candidates) {
            boolean present = versions.lock(row.recordId(), row.number()).isPresent();
            if (!present || index.find(row.jobId()).isEmpty() || !newest(row) || !readable(row.recordId())) {
                continue;
            }
            JobRef job = jobs.create(KIND, created -> index.move(row.jobId(), created.id())
                    .orElseThrow(() -> new IllegalStateException("artifacts.index_missing")));
            retried++;
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("artifactId", row.recordId())
                    .addKeyValue("failedJobId", row.jobId()).addKeyValue("jobId", job.id())
                    .addKeyValue("retries", row.retries() + 1).log("artifacts.index_retried");
        }
        return retried;
    }

    /** The ids of the owner's index jobs of these artifacts, every version, ended or not. */
    List<UUID> jobsOf(Collection<UUID> recordIds) {
        return jobIds(index.ofRecords(recordIds));
    }

    /** Cancels the owner's unended index jobs of these artifacts, in the caller's transaction; answers how many. */
    int cancel(Collection<UUID> recordIds) {
        int cancelled = 0;
        for (UUID jobId : jobs.unended(jobsOf(recordIds))) {
            cancelled += jobs.cancel(jobId) ? 1 : 0;
        }
        return cancelled;
    }

    /** Whether the row's version is its artifact's newest (Story 5.2: an older one is never indexed again). */
    private boolean newest(ArtifactIndexRow row) {
        return newest(row.recordId(), row.number());
    }

    /** Whether the version {@code number} is its artifact's newest (Story 5.2). */
    private boolean newest(UUID recordId, int number) {
        return versions.latest(recordId).map(ArtifactVersionRow::number).filter(latest -> latest == number)
                .isPresent();
    }

    /** Whether the owner's artifact's campaign is still readable: not being deleted, nor of a system being deleted. */
    private boolean readable(UUID recordId) {
        return records.findAll(List.of(recordId)).stream()
                .anyMatch(identity -> library.campaign(identity.campaignId()).isPresent());
    }

    private static List<UUID> jobIds(List<ArtifactIndexRow> rows) {
        return rows.stream().map(ArtifactIndexRow::jobId).toList();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
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
        ArtifactIndexRow row = index.find(job.id())
                .orElseThrow(() -> new IllegalStateException("artifacts.index_missing"));
        byte[] key = vault.ownerTokenKey(job.ownerId());
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        try {
            input.put("source", row.recordId().toString());
            input.put("owner", job.ownerId().toString());
            input.put("kind", ARTIFACT_KIND);
            input.putNull("language");
            input.put("tokenKey", Base64.getEncoder().encodeToString(key));
            input.put(RECORDS, RECORDS);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        return input;
    }

    @Override
    public Optional<JobInput> openInput(JobRef job, String name) {
        if (!RECORDS.equals(name)) {
            return Optional.empty();
        }
        Optional<ArtifactIndexRow> row = index.find(job.id());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        UUID recordId = row.get().recordId();
        Optional<ArtifactVersionRow> version = versions.find(recordId, row.get().number());
        Optional<ArtifactRecords.Identity> identity = records.findAll(List.of(recordId)).stream().findFirst();
        if (version.isEmpty() || identity.isEmpty()) {
            return Optional.empty();
        }
        Artifacts.Artifact artifact = Artifacts.open(vault, identity.get(), version.get(), job.ownerId());
        ObjectNode line = JsonNodeFactory.instance.objectNode();
        line.put("id", artifact.id().toString());
        line.put("page", 1);
        line.put("type", artifact.recordType());
        line.put("name", artifact.name());
        ArrayNode fields = line.putArray("fields");
        artifact.fields().forEach(field -> fields.addObject().put("name", field.name()).put("value", field.value()));
        if (artifact.content() != null) {
            fields.addObject().put("name", ScopedRecordContent.CONTENT_FIELD).put("value", artifact.content());
        }
        byte[] bytes = JSON.writeValueAsBytes(line);
        byte[] lines = Arrays.copyOf(bytes, bytes.length + 1);
        lines[bytes.length] = '\n';
        Arrays.fill(bytes, (byte) 0);
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("artifactId", recordId)
                .addKeyValue("version", row.get().number()).addKeyValue("fields", artifact.fields().size())
                .addKeyValue("content", artifact.content() != null).log("artifacts.index_opened");
        return Optional.of(new Lines(lines));
    }

    /**
     * An artifact's line, decrypted in the input's read transaction (a version is bounded: 64 fields of at most 8,000
     * code points and a content of at most 20,000), since the vault is not read once that transaction ended. Kept only
     * until it is written, then zeroed.
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
        ArtifactIndexRow row = index.find(job.id())
                .orElseThrow(() -> new JobResultInvalid("artifacts.index_missing"));
        if (versions.lock(row.recordId(), row.number()).isEmpty()) {
            throw new JobResultInvalid("artifacts.version_missing");
        }
        index.recordRecipe(job.id(), recipe);
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("artifactId", row.recordId())
                .addKeyValue("version", row.number()).addKeyValue("recipe", recipe).log("artifacts.index_done");
    }

    @Override
    public void failed(JobRef job, String code) {
        // The version keeps no recipe: the retry sweep gives its row a new job, at most MAX_RETRIES times, and after
        // that the next recipe the worker states starts it again (reconcile).
        log.atWarn().addKeyValue("jobId", job.id()).addKeyValue("code", code).log("artifacts.index_failed");
    }
}
