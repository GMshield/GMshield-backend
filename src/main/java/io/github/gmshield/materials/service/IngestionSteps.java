package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobInput;
import io.github.gmshield.jobs.service.JobOutput;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobResultInvalid;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.FileStore;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The ingestion chain of a material version (Story 1.15, AD-17): the job kind {@value #KIND} on the ingestion lane, one
 * job per step. The worker defines the stages and the page batches; the backend only records them.
 *
 * <ul>
 *   <li>{@link #start}: a completed PDF upload gets its first step (stage {@value #CLEAN}, the whole raw file), in the
 *       transaction that makes the version {@code waiting}. Other formats wait for their own stories.</li>
 *   <li>{@link #input}: at the claim, a {@code waiting} version becomes {@code processing}; the worker gets the stage,
 *       the page range and the name of its input ({@value #SOURCE}), which {@link #openInput} streams.</li>
 *   <li>{@link #completed}: one report, recorded in the claim's transaction: the pipeline version (the first report's
 *       is kept; another one fails the version with {@value #PIPELINE_CHANGED}), the page total, the cleaned original
 *       and the page batches (adopted from the job's outputs into {@code version_file}), and the next steps. Next
 *       steps that would take the version past {@link IngestionSettings#stepCap} fail it with
 *       {@value #TOO_MANY_STEPS}. Once an original is recorded the raw upload is marked {@code removing} in the same
 *       transaction, and its pieces go after the commit (the upload sweep finishes whatever is left).</li>
 *   <li>{@link #failed}: a step that ended failed fails the version with its code (a code outside the
 *       {@code ingestion} category is recorded as {@value #STEP_FAILED}). For {@value #PASSWORD_REQUIRED}, the raw
 *       upload and every file of the version are removed too; the material stays, so the GM sees why.</li>
 * </ul>
 *
 * Every call runs as the job's owner; nothing here logs a name, content or a file's bytes.
 */
@Service
public class IngestionSteps implements JobHandler, IngestionStart {

    public static final String KIND = "ingestion";
    /** The first stage: clean the raw file and split it into page batches. */
    public static final String CLEAN = "clean";
    /** The name of a step's input file in the internal API. */
    public static final String SOURCE = "source";
    public static final String PASSWORD_REQUIRED = "ingestion.password_required";
    public static final String TOO_MANY_STEPS = "ingestion.too_many_steps";
    public static final String STEP_FAILED = "ingestion.step_failed";
    public static final String PIPELINE_CHANGED = "ingestion.pipeline_changed";
    static final String PROCESSING = "processing";
    static final String CATEGORY_PREFIX = "ingestion.";
    static final Pattern STAGE = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    static final Pattern PIPELINE_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    static final int MAX_PAGES = 1_000_000;
    /** The formats with a clean stage in this release. */
    static final Set<String> FORMATS = Set.of("pdf");
    private static final Logger log = LoggerFactory.getLogger(IngestionSteps.class);

    private final Jobs jobs;
    private final MaterialStore materials;
    private final UploadStorage uploads;
    private final FileStore files;
    private final IngestionSettings settings;
    private final ObjectProvider<Clock> clock;

    IngestionSteps(Jobs jobs, MaterialStore materials, UploadStorage uploads, FileStore files, IngestionSettings settings,
                   ObjectProvider<Clock> clock) {
        this.jobs = jobs;
        this.materials = materials;
        this.uploads = uploads;
        this.files = files;
        this.settings = settings;
        this.clock = clock;
    }

    // --- the chain's start

    @Override
    public void start(VersionRow version, UploadRow upload) {
        if (!FORMATS.contains(version.format())) {
            return;
        }
        UUID owner = OwnerContext.require();
        Instant now = now();
        JobRef job = jobs.create(KIND, created -> materials.insertStep(new StepRow(Ids.newId(), owner, version.id(),
                created.id(), CLEAN, null, null, upload.id(), now)));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id())
                .addKeyValue("stage", CLEAN).log("ingestion.step_created");
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
        StepRow step = materials.findStepByJob(job.id()).orElseThrow(() -> new IllegalStateException("ingestion.step_missing"));
        VersionRow version = materials.findVersion(step.versionId())
                .orElseThrow(() -> new IllegalStateException("ingestion.version_missing"));
        if (Uploads.WAITING.equals(version.state())) {
            materials.changeVersionState(version.id(), PROCESSING, now());
            log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id()).log("ingestion.processing");
        } else if (!PROCESSING.equals(version.state())) {
            throw new IllegalStateException("ingestion.version_not_processing");
        }
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("versionId", version.id().toString());
        input.put("stage", step.stage());
        input.put("format", version.format());
        input.put("input", SOURCE);
        if (step.firstPage() != null) {
            input.put("firstPage", step.firstPage());
            input.put("lastPage", step.lastPage());
        }
        if (version.pageTotal() != null) {
            input.put("pageTotal", version.pageTotal());
        }
        if (version.pipelineVersion() != null) {
            input.put("pipelineVersion", version.pipelineVersion());
        }
        return input;
    }

    @Override
    public Optional<JobInput> openInput(JobRef job, String name) {
        if (!SOURCE.equals(name)) {
            return Optional.empty();
        }
        Optional<StepRow> step = materials.findStepByJob(job.id());
        Optional<VersionRow> version = step.flatMap(found -> materials.findVersion(found.versionId()))
                .filter(found -> PROCESSING.equals(found.state()));
        if (step.isEmpty() || version.isEmpty()) {
            return Optional.empty();
        }
        UUID fileId = step.get().inputFileId();
        UUID owner = job.ownerId();
        Optional<StoredFile> stored = materials.versionFiles(version.get().id()).stream()
                .filter(file -> file.fileId().equals(fileId)).findFirst()
                .map(file -> new StoredFile(owner, file.fileId(), file.sizeBytes(), file.pieceCount()))
                .or(() -> uploads.joined().completeForVersion(owner, version.get().id())
                        .filter(upload -> upload.id().equals(fileId))
                        .map(upload -> new StoredFile(owner, upload.id(), upload.length(), upload.pieceCount())));
        return stored.map(JobInput.class::cast);
    }

    /** A file of the store streamed piece by piece: one decrypted piece in memory at a time. */
    private final class StoredFile implements JobInput {
        private final UUID owner;
        private final UUID fileId;
        private final long size;
        private final int pieces;

        StoredFile(UUID owner, UUID fileId, long size, int pieces) {
            this.owner = owner;
            this.fileId = fileId;
            this.size = size;
            this.pieces = pieces;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            for (int index = 0; index < pieces; index++) {
                out.write(files.readPiece(owner, fileId, index));
            }
        }
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        Report report = Report.of(result);
        StepRow step = materials.findStepByJob(job.id()).orElseThrow(() -> new JobResultInvalid("ingestion.step_missing"));
        VersionRow version = materials.findVersion(step.versionId())
                .orElseThrow(() -> new JobResultInvalid("ingestion.version_missing"));
        if (!PROCESSING.equals(version.state())) {
            // Failed or discarded meanwhile: nothing is recorded, and the outputs go with the job.
            log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id())
                    .addKeyValue("state", version.state()).log("ingestion.report_ignored");
            return;
        }
        if (version.pipelineVersion() != null && !version.pipelineVersion().equals(report.pipelineVersion())) {
            fail(job, version, PIPELINE_CHANGED);
            return;
        }
        if (version.pageTotal() != null && report.pageTotal() != null && !version.pageTotal().equals(report.pageTotal())) {
            throw new JobResultInvalid("ingestion.page_total_changed");
        }
        Integer total = version.pageTotal() != null ? version.pageTotal() : report.pageTotal();
        if (total == null && (report.original() != null || !report.batches().isEmpty() || !report.next().isEmpty())) {
            throw new JobResultInvalid("ingestion.page_total_missing");
        }
        if (materials.countSteps(version.id()) + report.next().size() > settings.stepCap(total == null ? 0 : total)) {
            fail(job, version, TOO_MANY_STEPS);
            return;
        }
        report.checkPages(total == null ? 0 : total);
        Instant now = now();
        Map<UUID, VersionFileRow> known = new HashMap<>();
        materials.versionFiles(version.id()).forEach(file -> known.put(file.fileId(), file));
        if (report.original() != null) {
            VersionFileRow original = adopt(job, version, report.original(), VersionFileRow.ORIGINAL, 1, total, now);
            known.put(original.fileId(), original);
        }
        for (Report.Batch batch : report.batches()) {
            VersionFileRow adopted = adopt(job, version, batch.fileId(), VersionFileRow.BATCH, batch.firstPage(),
                    batch.lastPage(), now);
            known.put(adopted.fileId(), adopted);
        }
        for (Report.Next next : report.next()) {
            if (!known.containsKey(next.input())) {
                throw new JobResultInvalid("ingestion.next_input_unknown");
            }
            jobs.create(KIND, created -> materials.insertStep(new StepRow(Ids.newId(), job.ownerId(), version.id(),
                    created.id(), next.stage(), next.firstPage(), next.lastPage(), next.input(), now)));
        }
        materials.recordIngestion(version.id(), report.pipelineVersion(), total, now);
        if (report.original() != null) {
            removeRawUpload(job.ownerId(), version.id(), now);
        }
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id())
                .addKeyValue("pageTotal", total == null ? 0 : total).addKeyValue("batches", report.batches().size())
                .addKeyValue("next", report.next().size()).log("ingestion.recorded");
    }

    @Override
    public void failed(JobRef job, String code) {
        Optional<VersionRow> version = materials.findStepByJob(job.id())
                .flatMap(step -> materials.findVersion(step.versionId()))
                .filter(found -> Uploads.WAITING.equals(found.state()) || PROCESSING.equals(found.state()));
        if (version.isEmpty()) {
            return;
        }
        String recorded = code != null && code.startsWith(CATEGORY_PREFIX) ? code : STEP_FAILED;
        fail(job, version.get(), recorded);
        if (PASSWORD_REQUIRED.equals(recorded)) {
            Instant now = now();
            removeRawUpload(job.ownerId(), version.get().id(), now);
            List<VersionFileRow> removed = materials.deleteVersionFiles(version.get().id());
            afterCommit(() -> removed.forEach(file -> files.deleteFile(file.ownerId(), file.fileId())));
        }
    }

    // --- recording

    private void fail(JobRef job, VersionRow version, String code) {
        materials.failVersion(version.id(), code, now());
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id()).addKeyValue("code", code)
                .log("ingestion.failed");
    }

    private VersionFileRow adopt(JobRef job, VersionRow version, UUID fileId, String role, int firstPage, int lastPage,
                                 Instant now) {
        JobOutput output = jobs.adoptOutput(job, fileId)
                .orElseThrow(() -> new JobResultInvalid("ingestion.output_unknown"));
        VersionFileRow row = new VersionFileRow(Ids.newId(), job.ownerId(), version.id(), output.fileId(), role,
                firstPage, lastPage, output.sizeBytes(), output.pieceCount(), now);
        materials.insertVersionFile(row);
        return row;
    }

    /** The raw upload of the version becomes {@code removing}; its pieces and its row go after the commit. */
    private void removeRawUpload(UUID owner, UUID versionId, Instant now) {
        Optional<UploadRow> upload = uploads.joined().completeForVersion(owner, versionId);
        if (upload.isEmpty() || !uploads.joined().removeComplete(upload.get().id(), now)) {
            return;
        }
        UploadRow row = upload.get();
        afterCommit(() -> {
            files.deleteFile(row.ownerId(), row.id());
            uploads.separately(store -> store.deleteRemoving(row.id()));
            log.atInfo().addKeyValue("ownerId", row.ownerId()).addKeyValue("uploadId", row.id()).log("upload.removed");
        });
    }

    /** Work after the commit, never the caller's failure: what it leaves, the sweeps finish. */
    private static void afterCommit(Runnable work) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    work.run();
                } catch (RuntimeException failure) {
                    log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("ingestion.removal_deferred");
                }
            }
        });
    }

    private Instant now() {
        return clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * A step's report, checked before anything is written: {@code pipelineVersion} (required), {@code pageTotal},
     * {@code original} ({@code fileId}), {@code batches} ({@code fileId}, {@code firstPage}, {@code lastPage}) and
     * {@code next} ({@code stage}, {@code firstPage}, {@code lastPage}, {@code input}: a file id of the version).
     */
    record Report(String pipelineVersion, Integer pageTotal, UUID original, List<Batch> batches, List<Next> next) {

        record Batch(UUID fileId, int firstPage, int lastPage) {
        }

        record Next(String stage, Integer firstPage, Integer lastPage, UUID input) {
        }

        static Report of(JsonNode result) {
            if (result == null || !result.isObject()) {
                throw new JobResultInvalid("ingestion.report_invalid");
            }
            JsonNode version = result.path("pipelineVersion");
            if (!version.isString() || !PIPELINE_VERSION.matcher(version.asString()).matches()) {
                throw new JobResultInvalid("ingestion.pipeline_version_invalid");
            }
            Integer total = optionalPage(result.path("pageTotal"));
            UUID original = null;
            JsonNode originalNode = result.path("original");
            if (!originalNode.isMissingNode() && !originalNode.isNull()) {
                original = fileId(originalNode.path("fileId"));
            }
            List<Batch> batches = new ArrayList<>();
            for (JsonNode node : array(result.path("batches"))) {
                batches.add(new Batch(fileId(node.path("fileId")), page(node.path("firstPage")), page(node.path("lastPage"))));
            }
            List<Next> next = new ArrayList<>();
            for (JsonNode node : array(result.path("next"))) {
                JsonNode stage = node.path("stage");
                if (!stage.isString() || !STAGE.matcher(stage.asString()).matches()) {
                    throw new JobResultInvalid("ingestion.stage_invalid");
                }
                Integer first = optionalPage(node.path("firstPage"));
                Integer last = optionalPage(node.path("lastPage"));
                if ((first == null) != (last == null)) {
                    throw new JobResultInvalid("ingestion.pages_invalid");
                }
                next.add(new Next(stage.asString(), first, last, fileId(node.path("input"))));
            }
            return new Report(version.asString(), total, original, List.copyOf(batches), List.copyOf(next));
        }

        /** Every page range lies within 1 to {@code total}, first before last. */
        void checkPages(int total) {
            for (Batch batch : batches) {
                range(batch.firstPage(), batch.lastPage(), total);
            }
            for (Next step : next) {
                if (step.firstPage() != null) {
                    range(step.firstPage(), step.lastPage(), total);
                }
            }
        }

        private static void range(int first, int last, int total) {
            if (first < 1 || last < first || last > total) {
                throw new JobResultInvalid("ingestion.pages_invalid");
            }
        }

        private static List<JsonNode> array(JsonNode node) {
            if (node.isMissingNode() || node.isNull()) {
                return List.of();
            }
            if (!node.isArray() || node.size() > MAX_PAGES) {
                throw new JobResultInvalid("ingestion.report_invalid");
            }
            List<JsonNode> items = new ArrayList<>(node.size());
            for (int i = 0; i < node.size(); i++) {
                if (!node.get(i).isObject()) {
                    throw new JobResultInvalid("ingestion.report_invalid");
                }
                items.add(node.get(i));
            }
            return items;
        }

        private static Integer optionalPage(JsonNode node) {
            return node.isMissingNode() || node.isNull() ? null : page(node);
        }

        private static int page(JsonNode node) {
            if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1 || node.intValue() > MAX_PAGES) {
                throw new JobResultInvalid("ingestion.pages_invalid");
            }
            return node.intValue();
        }

        private static UUID fileId(JsonNode node) {
            if (!node.isString() || !UUID_TEXT.matcher(node.asString()).matches()) {
                throw new JobResultInvalid("ingestion.file_id_invalid");
            }
            return UUID.fromString(node.asString());
        }
    }
}
