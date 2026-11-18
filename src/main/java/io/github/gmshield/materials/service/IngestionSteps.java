package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobInput;
import io.github.gmshield.jobs.service.JobOutput;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobResultInvalid;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.OutlineRow;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.PageViewRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The ingestion chain of a material version (Stories 1.15 and 1.16, AD-17): the job kind {@value #KIND} on the
 * ingestion lane, one job per step. The worker defines the stages and the page batches; the backend only records them,
 * and names a stage only to start or restart a chain ({@link #START_STAGES}).
 *
 * <ul>
 *   <li>{@link #start}: a completed PDF upload gets its first step (stage {@value #CLEAN}, the whole raw file), a
 *       completed TXT or Markdown upload its only one (stage {@value #TEXT_STAGE}), in the transaction that makes the
 *       version {@code waiting}. From Story 1.21, an image set (an image alone is a set of one) whose every image is
 *       complete gets its first step (stage {@value #IMAGES_STAGE}), which reads them all, in order.</li>
 *   <li>{@link #input}: at the claim, a {@code waiting} version becomes {@code processing}; the worker gets the stage,
 *       the attempt, the page range, the chain's page total and pipeline version, the name of its input
 *       ({@value #SOURCE}), which {@link #openInput} streams, and, when that input is a file of the version, its id
 *       and the pages it holds. A step over every text file of the version (Story 1.18) gets the input
 *       {@value #TEXTS} instead and {@code texts}, the name ({@code text-<fileId>}) and pages of each text file of
 *       the version in page order, each of which {@link #openInput} streams to that step only. A step of an image set
 *       whose input is not a file of the version (Story 1.21) gets the input {@value #IMAGES_INPUT} instead and
 *       {@code inputs}, the name ({@code image-<uploadId>}) and format of each complete image of the set in its
 *       order, each of which {@link #openInput} streams to that step only.</li>
 *   <li>{@link #completed}: one report, recorded in the claim's transaction with the version locked: the pipeline
 *       version (the first report's is kept; a report under another one restarts the chain), the page total, the
 *       cleaned original (a later one replaces it), the page batches and the {@code texts} files (adopted from the
 *       job's outputs into {@code version_file}), the pages' layers and outcomes (as ranges; a page given a second
 *       layer or outcome, or a {@code texts} range over a page not read, refuses the report), and the next steps (one
 *       whose input is {@value #TEXTS} is held: it gets no job yet). From Story 1.18, a report carrying
 *       {@code passages} replaces the version's passages and outline, each passage's text and place and the outline
 *       encrypted. From Story 1.19, a report's {@code pageViews} replace the page views of the pages they name: each
 *       picture is adopted as a {@code picture} file of its page, and each page's lines or text are encrypted. Next
 *       steps that would take the version past {@link IngestionSettings#stepCap} fail it with
 *       {@value #TOO_MANY_STEPS}.
 *       Once an original is recorded the raw upload is marked {@code removing} in the same transaction, and its pieces
 *       go after the commit (the upload sweep finishes whatever is left). A report that leaves every page with an
 *       outcome and only held steps unended gives each held step its job; one that leaves no step unended while
 *       every page has an outcome makes the version {@code needs_review}.</li>
 *   <li>{@link #failed}: a step that ended failed fails the version with its code (a code outside the
 *       {@code ingestion} category is recorded as {@value #STEP_FAILED}), and cancels its other steps (a held step
 *       just ends). For
 *       {@value #PASSWORD_REQUIRED}, the raw upload, every page view and every file of the version are removed too; the
 *       material stays,
 *       so the GM sees why. {@value #PIPELINE_CHANGED} restarts the chain instead.</li>
 *   <li>A restart (Story 1.16), in one transaction: the version's other unended step jobs are cancelled (held steps
 *       end), its layers, outcomes, batches and {@code texts} files, passages and outline, page views and pictures go,
 *       its pipeline version is cleared (its page total stays), and a new first step reads the stored original.</li>
 *   <li>The GM's review (Story 1.20), each in the caller's transaction with the version locked: {@link #reprocess}
 *       reads the unreadable pages of a {@code needs_review} version again, by copies of the steps that read them;
 *       {@link #retry} starts a {@code failed} version again, from its original as a restart does or from its raw
 *       upload as {@link #start} does; {@link #cancelSteps} ends a discarded version's steps.</li>
 * </ul>
 *
 * Every step row gets {@code ended_at} when its job is completed, finally fails or is cancelled here. Every call runs
 * as the job's owner; nothing here logs a name, content or a file's bytes.
 */
@Service
public class IngestionSteps implements JobHandler, IngestionStart {

    public static final String KIND = "ingestion";
    /** The first stage of a PDF: clean the raw file and split it into page batches. */
    public static final String CLEAN = "clean";
    /** The only stage of a text upload (Story 1.16): split it into pages of bounded length. */
    public static final String TEXT_STAGE = "text";
    /** The name of a step's input file in the internal API. */
    public static final String SOURCE = "source";
    /** A step's input that is every text file of its version (Story 1.18); each one's input name is its prefix. */
    public static final String TEXTS = "texts";
    static final String TEXT_INPUT = "text-";
    /** The first stage of an image set (Story 1.21): assemble its images, in order, into one PDF. */
    public static final String IMAGES_STAGE = "images";
    /** A step's input that is every image of its set (Story 1.21); each one's input name is its prefix. */
    public static final String IMAGES_INPUT = "images";
    static final String IMAGE_INPUT = "image-";
    public static final String PASSWORD_REQUIRED = "ingestion.password_required";
    public static final String TOO_MANY_STEPS = "ingestion.too_many_steps";
    public static final String STEP_FAILED = "ingestion.step_failed";
    public static final String PIPELINE_CHANGED = "ingestion.pipeline_changed";
    static final String PROCESSING = "processing";
    static final String NEEDS_REVIEW = "needs_review";
    static final String CATEGORY_PREFIX = "ingestion.";
    static final Pattern STAGE = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    static final Pattern PIPELINE_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    static final int MAX_PAGES = 1_000_000;
    /** A passage's text, at most, in code points (Story 1.18). */
    static final int MAX_PASSAGE = 16_384;
    /** A section path's depth, at most, a heading title's code points, at most, and an outline level's. */
    static final int MAX_DEPTH = 32;
    static final int MAX_TITLE = 1_000;
    static final int MAX_LEVEL = 1_000;
    static final String PASSAGE_INVALID = "ingestion.passage_invalid";
    static final String OUTLINE_INVALID = "ingestion.outline_invalid";
    /** A page view's picture side, at most, in pixels; its lines and each line's code points; a text page's text. */
    static final int MAX_SIDE = 10_000;
    static final int MAX_LINES = 10_000;
    static final int MAX_LINE = 4_096;
    static final int MAX_PAGE_TEXT = 16_384;
    static final String PAGE_VIEW_INVALID = "ingestion.page_view_invalid";
    /** The files a step may read or a next step may name: never the pictures, which may be one per page. */
    static final Set<String> STEP_FILES = Set.of(VersionFileRow.ORIGINAL, VersionFileRow.BATCH, VersionFileRow.TEXT);
    /** The first stage of each format with a chain in this release; the stage a restart starts from too. */
    static final Map<String, String> START_STAGES = Map.of("pdf", CLEAN, "txt", TEXT_STAGE, "markdown", TEXT_STAGE,
            Format.IMAGES, IMAGES_STAGE);
    static final Set<String> LAYERS = Set.of(PageRangeRow.TEXT, PageRangeRow.IMAGE);
    static final Set<String> OUTCOMES = Set.of(PageRangeRow.READ_DIRECTLY, PageRangeRow.READ_BY_OCR,
            PageRangeRow.UNREADABLE);
    private static final Logger log = LoggerFactory.getLogger(IngestionSteps.class);

    private final Jobs jobs;
    private final MaterialStore materials;
    private final UploadStorage uploads;
    private final FileStore files;
    private final IngestionSettings settings;
    private final Vault vault;
    private final ObjectProvider<Clock> clock;

    IngestionSteps(Jobs jobs, MaterialStore materials, UploadStorage uploads, FileStore files, IngestionSettings settings,
                   Vault vault, ObjectProvider<Clock> clock) {
        this.jobs = jobs;
        this.materials = materials;
        this.uploads = uploads;
        this.files = files;
        this.settings = settings;
        this.vault = vault;
        this.clock = clock;
    }

    // --- the chain's start

    @Override
    public void start(VersionRow version, UploadRow upload) {
        String stage = START_STAGES.get(version.format());
        if (stage == null) {
            return;
        }
        UUID owner = OwnerContext.require();
        Instant now = now();
        JobRef job = jobs.create(KIND, created -> materials.insertStep(new StepRow(Ids.newId(), owner, version.id(),
                created.id(), stage, null, null, upload.id(), now)));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id())
                .addKeyValue("stage", stage).log("ingestion.step_created");
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
        input.put("attempt", job.attempt());
        if (step.inputTexts()) {
            input.put("input", TEXTS);
            ArrayNode texts = input.putArray("texts");
            textFiles(version.id()).forEach(file -> texts.addObject().put("name", TEXT_INPUT + file.fileId())
                    .put("firstPage", file.firstPage()).put("lastPage", file.lastPage()));
        } else if (readsImages(step, version)) {
            input.put("input", IMAGES_INPUT);
            ArrayNode images = input.putArray("inputs");
            uploads.joined().completeOfVersion(job.ownerId(), version.id()).forEach(upload -> images.addObject()
                    .put("name", IMAGE_INPUT + upload.id()).put("format", upload.format()));
        } else {
            input.put("input", SOURCE);
            Optional.ofNullable(step.inputFileId()).flatMap(fileId -> materials.versionFile(version.id(), fileId))
                    .filter(file -> STEP_FILES.contains(file.role())).ifPresent(file -> {
                        input.put("inputFileId", file.fileId().toString());
                        input.put("inputFirstPage", file.firstPage());
                        input.put("inputLastPage", file.lastPage());
                    });
        }
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

    /**
     * Whether the step reads the images of its set (Story 1.21): a step of an image version whose input is not one of
     * the version's files (it names the set's first image). A restart reads the stored original instead.
     */
    private boolean readsImages(StepRow step, VersionRow version) {
        return !step.inputTexts() && Format.IMAGES.equals(version.format()) && (step.inputFileId() == null
                || materials.versionFile(version.id(), step.inputFileId()).filter(file -> STEP_FILES.contains(
                file.role())).isEmpty());
    }

    /** The version's text files, in page order. */
    private List<VersionFileRow> textFiles(UUID versionId) {
        return materials.versionFiles(versionId, Set.of(VersionFileRow.TEXT)).stream()
                .sorted(Comparator.comparingInt(VersionFileRow::firstPage)).toList();
    }

    @Override
    public Optional<JobInput> openInput(JobRef job, String name) {
        Optional<StepRow> step = materials.findStepByJob(job.id());
        Optional<VersionRow> version = step.flatMap(found -> materials.findVersion(found.versionId()))
                .filter(found -> PROCESSING.equals(found.state()));
        if (step.isEmpty() || version.isEmpty()) {
            return Optional.empty();
        }
        UUID owner = job.ownerId();
        if (step.get().inputTexts()) {
            // A step over every text file reads those files, by name, and nothing else.
            return textFiles(version.get().id()).stream().filter(file -> (TEXT_INPUT + file.fileId()).equals(name))
                    .findFirst().map(file -> new StoredFile(owner, file.fileId(), file.sizeBytes(), file.pieceCount()));
        }
        if (readsImages(step.get(), version.get())) {
            // A step over the images of a set reads them, by name, and nothing else.
            return uploads.joined().completeOfVersion(owner, version.get().id()).stream()
                    .filter(upload -> (IMAGE_INPUT + upload.id()).equals(name)).findFirst()
                    .map(upload -> new StoredFile(owner, upload.id(), upload.length(), upload.pieceCount()));
        }
        if (!SOURCE.equals(name)) {
            return Optional.empty();
        }
        UUID fileId = step.get().inputFileId();
        Optional<StoredFile> stored = Optional.ofNullable(fileId)
                .flatMap(found -> materials.versionFile(version.get().id(), found))
                .filter(file -> STEP_FILES.contains(file.role()))
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
        VersionRow version = materials.lockVersion(step.versionId())
                .orElseThrow(() -> new JobResultInvalid("ingestion.version_missing"));
        Instant now = now();
        materials.endStep(job.id(), now);
        if (!PROCESSING.equals(version.state())) {
            // Failed or discarded meanwhile: nothing is recorded, and the outputs go with the job.
            log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id())
                    .addKeyValue("state", version.state()).log("ingestion.report_ignored");
            return;
        }
        if (version.pipelineVersion() != null && !version.pipelineVersion().equals(report.pipelineVersion())) {
            // A report under another pipeline counts as the step failing with pipeline_changed.
            restart(job, version, now);
            return;
        }
        // A chain's first report (a new chain, or a restarted one) sets the page total, even over the kept one: another
        // pipeline may split the same file into other pages. Every later report must keep it.
        boolean first = version.pipelineVersion() == null;
        if (!first && version.pageTotal() != null && report.pageTotal() != null
                && !version.pageTotal().equals(report.pageTotal())) {
            throw new JobResultInvalid("ingestion.page_total_changed");
        }
        Integer total = (first || version.pageTotal() == null) && report.pageTotal() != null ? report.pageTotal()
                : version.pageTotal();
        if (total == null && report.namesPages()) {
            throw new JobResultInvalid("ingestion.page_total_missing");
        }
        if (materials.countSteps(version.id()) + report.next().size() > settings.stepCap(total == null ? 0 : total)) {
            fail(job, version, TOO_MANY_STEPS, now);
            return;
        }
        report.checkPages(total == null ? 0 : total);
        Map<UUID, VersionFileRow> known = new HashMap<>();
        materials.versionFiles(version.id(), STEP_FILES).forEach(file -> known.put(file.fileId(), file));
        if (report.original() != null) {
            // A restarted chain's clean report replaces the original it read.
            List<VersionFileRow> replaced = materials.deleteVersionFiles(version.id(), Set.of(VersionFileRow.ORIGINAL));
            replaced.forEach(file -> known.remove(file.fileId()));
            removeFilesAfterCommit(replaced);
            VersionFileRow original = adopt(job, version, report.original(), VersionFileRow.ORIGINAL, 1, total, now);
            known.put(original.fileId(), original);
        }
        for (Report.Batch batch : report.batches()) {
            VersionFileRow adopted = adopt(job, version, batch.fileId(), VersionFileRow.BATCH, batch.firstPage(),
                    batch.lastPage(), now);
            known.put(adopted.fileId(), adopted);
        }
        for (Report.Batch text : report.texts()) {
            adopt(job, version, text.fileId(), VersionFileRow.TEXT, text.firstPage(), text.lastPage(), now);
        }
        recordRanges(job, version, report, now);
        if (report.carriesPassages()) {
            replacePassages(job, version.id(), report, now);
        }
        if (!report.pageViews().isEmpty()) {
            replacePageViews(job, version, report, now);
        }
        for (Report.Next next : report.next()) {
            if (next.texts()) {
                // Its input files do not all exist yet: the step waits for its job until every page has an outcome.
                materials.insertStep(StepRow.held(Ids.newId(), job.ownerId(), version.id(), next.stage(),
                        next.firstPage(), next.lastPage(), now));
                continue;
            }
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
                .addKeyValue("layers", report.layers().size()).addKeyValue("outcomes", report.pages().size())
                .addKeyValue("texts", report.texts().size()).addKeyValue("next", report.next().size())
                .addKeyValue("passages", report.carriesPassages() ? report.passages().size() : 0)
                .addKeyValue("pageViews", report.pageViews().size()).log("ingestion.recorded");
        endChainIfDone(job, version.id(), total, now);
    }

    @Override
    public void failed(JobRef job, String code) {
        Optional<StepRow> step = materials.findStepByJob(job.id());
        if (step.isEmpty() || step.get().endedAt() != null) {
            // Unknown, or ended already (cancelled by a restart): nothing more to record.
            return;
        }
        Instant now = now();
        materials.endStep(job.id(), now);
        Optional<VersionRow> version = materials.lockVersion(step.get().versionId())
                .filter(found -> Uploads.WAITING.equals(found.state()) || PROCESSING.equals(found.state()));
        if (version.isEmpty()) {
            return;
        }
        if (PIPELINE_CHANGED.equals(code)) {
            restart(job, version.get(), now);
            return;
        }
        String recorded = code != null && code.startsWith(CATEGORY_PREFIX) ? code : STEP_FAILED;
        fail(job, version.get(), recorded, now);
        if (PASSWORD_REQUIRED.equals(recorded)) {
            removeRawUpload(job.ownerId(), version.get().id(), now);
            materials.deletePageViews(version.get().id());
            removeFilesAfterCommit(materials.deleteVersionFiles(version.get().id()));
        }
    }

    // --- recording

    /** The version fails with {@code code}; its other steps that have not ended are cancelled and end. */
    private void fail(JobRef job, VersionRow version, String code, Instant now) {
        materials.failVersion(version.id(), code, now);
        int cancelled = cancelUnended(version.id(), now);
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id()).addKeyValue("code", code)
                .addKeyValue("cancelled", cancelled).log("ingestion.failed");
    }

    /**
     * Restarts the chain from the stored original (Story 1.16): see {@link #startOver}. Without an original, or past
     * the step cap, the version fails instead.
     */
    private void restart(JobRef job, VersionRow version, Instant now) {
        Optional<VersionFileRow> original = original(version.id());
        String stage = START_STAGES.get(version.format());
        if (original.isEmpty() || stage == null) {
            fail(job, version, PIPELINE_CHANGED, now);
            return;
        }
        if (pastCap(version, 1)) {
            fail(job, version, TOO_MANY_STEPS, now);
            return;
        }
        StartedOver started = startOver(version, stage, original.get().fileId(), now);
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id())
                .addKeyValue("newJobId", started.jobId()).addKeyValue("stage", stage)
                .addKeyValue("cancelled", started.cancelled()).addKeyValue("ranges", started.ranges())
                .addKeyValue("passages", started.passages()).addKeyValue("outline", started.outline())
                .addKeyValue("pageViews", started.pageViews()).log("ingestion.restarted");
    }

    /** What {@link #startOver} removed, and its new first step's job. */
    private record StartedOver(UUID jobId, int cancelled, int ranges, int passages, boolean outline, int pageViews) {
    }

    /**
     * The chain of the version starts over, needing no job (Stories 1.16 and 1.20), in the caller's transaction as the
     * version's owner: the other unended steps are cancelled (held steps end); the layers, outcomes, batches and
     * {@code texts} files go, and the passages, outline, page views and pictures; the pipeline version is cleared and
     * the page total kept; a new first step of {@code stage} reads {@code input} (the stored original, or the raw
     * upload of a version that has none).
     */
    private StartedOver startOver(VersionRow version, String stage, UUID input, Instant now) {
        UUID owner = OwnerContext.require();
        int cancelled = cancelUnended(version.id(), now);
        int ranges = materials.deletePageRanges(version.id());
        int passages = materials.deletePassages(version.id());
        boolean outline = materials.deleteOutline(version.id());
        // Page views first: each names its picture file.
        int pageViews = materials.deletePageViews(version.id());
        removeFilesAfterCommit(materials.deleteVersionFiles(version.id(),
                Set.of(VersionFileRow.BATCH, VersionFileRow.TEXT, VersionFileRow.PICTURE)));
        materials.clearPipelineVersion(version.id(), now);
        JobRef first = jobs.create(KIND, created -> materials.insertStep(new StepRow(Ids.newId(), owner,
                version.id(), created.id(), stage, null, null, input, now)));
        return new StartedOver(first.id(), cancelled, ranges, passages, outline, pageViews);
    }

    private Optional<VersionFileRow> original(UUID versionId) {
        return materials.versionFiles(versionId, Set.of(VersionFileRow.ORIGINAL)).stream().findFirst();
    }

    /** Whether {@code added} more steps would take the version past its step cap. */
    private boolean pastCap(VersionRow version, int added) {
        int total = version.pageTotal() == null ? 0 : version.pageTotal();
        return materials.countSteps(version.id()) + added > settings.stepCap(total);
    }

    // --- the GM's review (Story 1.20)

    /**
     * What a failed version would start again from: its cleaned original, else its complete raw upload; empty when it
     * has neither, or when its format has no chain. The caller has the version's owner bound.
     */
    private static Optional<UUID> retryInput(MaterialStore materials, UploadStorage uploads, VersionRow version) {
        if (version.format() == null || !START_STAGES.containsKey(version.format())) {
            return Optional.empty();
        }
        return materials.versionFiles(version.id(), Set.of(VersionFileRow.ORIGINAL)).stream().findFirst()
                .map(VersionFileRow::fileId)
                .or(() -> uploads.joined().completeForVersion(OwnerContext.require(), version.id())
                        .map(UploadRow::id));
    }

    /** Whether a failed version would be retried now: {@link #retryCheck}. */
    enum RetryCheck {
        /** Not failed, or nothing remains to start from (or its format has no chain). */
        NO_INPUT,
        /** An input remains, but one more step would pass the step cap of its known page total. */
        PAST_CAP,
        /** {@link #retry} would accept it. */
        ALLOWED
    }

    /**
     * The one check behind a failed version's {@code retry}, in its {@code actions} and in the locked action (Story
     * 1.20): an input remains ({@link #retryInput}) and either its page total is not known yet (its first step failed:
     * each retry is the GM's own) or one more step stays within the step cap. The caller has the owner bound.
     */
    static RetryCheck retryCheck(MaterialStore materials, UploadStorage uploads, IngestionSettings settings,
                                 VersionRow version) {
        if (!Materials.FAILED.equals(version.state()) || retryInput(materials, uploads, version).isEmpty()) {
            return RetryCheck.NO_INPUT;
        }
        if (version.pageTotal() != null
                && materials.countSteps(version.id()) + 1 > settings.stepCap(version.pageTotal())) {
            return RetryCheck.PAST_CAP;
        }
        return RetryCheck.ALLOWED;
    }

    /**
     * Retries a {@code failed} version, locked by the caller, in its transaction: from its cleaned original as a
     * restart does ({@link #startOver}), or without one from its complete raw upload as {@link #start} does; the
     * version becomes {@code processing}. Refused with {@code material.action_unavailable} when it has neither, and
     * with {@value #TOO_MANY_STEPS} when the new first step would pass the step cap of its known page total (nothing
     * changes). A version whose page total is not known yet (its first step failed) has no cap to pass: each retry is
     * the GM's own.
     */
    public void retry(VersionRow version) {
        RetryCheck check = retryCheck(materials, uploads, settings, version);
        if (check == RetryCheck.NO_INPUT) {
            throw MaterialProblems.actionUnavailable();
        }
        if (check == RetryCheck.PAST_CAP) {
            throw MaterialProblems.tooManySteps();
        }
        Optional<UUID> input = retryInput(materials, uploads, version);
        Instant now = now();
        String stage = START_STAGES.get(version.format());
        StartedOver started = startOver(version, stage, input.orElseThrow(), now);
        materials.changeVersionState(version.id(), PROCESSING, now);
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("newJobId", started.jobId())
                .addKeyValue("stage", stage).addKeyValue("ranges", started.ranges())
                .addKeyValue("pageViews", started.pageViews()).log("ingestion.retried");
    }

    /** A new step that reads again a run of unreadable pages, as {@code step} read them. */
    private record Reread(StepRow step, int firstPage, int lastPage) {
    }

    /**
     * Reprocesses the unreadable pages of a {@code needs_review} version, locked by the caller, in its transaction.
     * For each unreadable page, the narrowest recorded step with a page range that covers it (the latest of equally
     * narrow ones; never a step over every text file, nor one whose input is no longer a file of the version) is
     * copied, its stage and input file, narrowed to each run of consecutive pages it covers: the backend never names a
     * stage (AD-15). The version's {@code unreadable} outcomes
     * go, with those pages' page views and pictures, and its passages and outline; a held copy of its latest step over
     * every text file is added; the version becomes {@code processing}, and the chain's end brings it back to
     * {@code needs_review}. Refused with {@code material.action_unavailable} when no page is unreadable or one has no
     * such covering step, and with {@value #TOO_MANY_STEPS} when the new steps would pass the step cap; a refusal
     * changes nothing.
     */
    public void reprocess(VersionRow version) {
        if (!NEEDS_REVIEW.equals(version.state())) {
            throw MaterialProblems.actionUnavailable();
        }
        List<PageRangeRow> unreadable = materials.pageOutcomes(List.of(version.id())).stream()
                .filter(range -> PageRangeRow.UNREADABLE.equals(range.value()))
                .sorted(Comparator.comparingInt(PageRangeRow::firstPage)).toList();
        if (unreadable.isEmpty()) {
            throw MaterialProblems.actionUnavailable();
        }
        List<StepRow> steps = materials.steps(version.id());
        // Only steps whose input is still a file of the version: a restart or a retry removed the batches that the
        // steps of an earlier chain read, and those steps stay recorded.
        Set<UUID> inputs = new HashSet<>();
        materials.versionFiles(version.id(), STEP_FILES).forEach(file -> inputs.add(file.fileId()));
        List<Reread> rereads = rereads(unreadable, steps.stream().filter(step -> step.firstPage() != null
                && step.lastPage() != null && !step.inputTexts() && inputs.contains(step.inputFileId())).toList());
        Optional<StepRow> passages = steps.stream().filter(StepRow::inputTexts).reduce((first, second) -> second);
        if (pastCap(version, rereads.size() + (passages.isPresent() ? 1 : 0))) {
            throw MaterialProblems.tooManySteps();
        }
        UUID owner = OwnerContext.require();
        Instant now = now();
        int outcomes = materials.deleteOutcomes(version.id(), PageRangeRow.UNREADABLE);
        List<PageViewRow> views = new ArrayList<>();
        for (int[] run : merged(unreadable)) {
            views.addAll(materials.deletePageViews(version.id(), run[0], run[1]));
        }
        removeFilesAfterCommit(views.stream().filter(PageViewRow::hasPicture)
                .flatMap(view -> materials.deleteVersionFile(version.id(), view.pictureFileId()).stream()).toList());
        int removedPassages = materials.deletePassages(version.id());
        materials.deleteOutline(version.id());
        passages.ifPresent(held -> materials.insertStep(StepRow.held(Ids.newId(), owner, version.id(), held.stage(),
                held.firstPage(), held.lastPage(), now)));
        materials.changeVersionState(version.id(), PROCESSING, now);
        for (Reread reread : rereads) {
            jobs.create(KIND, created -> materials.insertStep(new StepRow(Ids.newId(), owner, version.id(),
                    created.id(), reread.step().stage(), reread.firstPage(), reread.lastPage(),
                    reread.step().inputFileId(), now)));
        }
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("steps", rereads.size())
                .addKeyValue("outcomes", outcomes).addKeyValue("pageViews", views.size())
                .addKeyValue("passages", removedPassages).addKeyValue("held", passages.isPresent())
                .log("ingestion.reprocessed");
    }

    /**
     * The steps that read the unreadable pages again: for each page, the narrowest of {@code steps} that covers it (the
     * latest of equally narrow ones), consecutive pages under the same step grouped into one run. Refused with
     * {@code material.action_unavailable} when a page has none.
     */
    private static List<Reread> rereads(List<PageRangeRow> unreadable, List<StepRow> steps) {
        List<Reread> rereads = new ArrayList<>();
        for (PageRangeRow range : unreadable) {
            List<StepRow> overlapping = steps.stream().filter(step -> step.firstPage() <= range.lastPage()
                    && step.lastPage() >= range.firstPage()).toList();
            for (int page = range.firstPage(); page <= range.lastPage(); page++) {
                StepRow narrowest = narrowest(overlapping, page);
                if (narrowest == null) {
                    throw MaterialProblems.actionUnavailable();
                }
                Reread last = rereads.isEmpty() ? null : rereads.getLast();
                if (last != null && last.step().id().equals(narrowest.id()) && last.lastPage() == page - 1) {
                    rereads.set(rereads.size() - 1, new Reread(narrowest, last.firstPage(), page));
                } else {
                    rereads.add(new Reread(narrowest, page, page));
                }
            }
        }
        return rereads;
    }

    /** The narrowest step that covers the page, the latest of equally narrow ones (steps are oldest first); or null. */
    private static StepRow narrowest(List<StepRow> steps, int page) {
        StepRow found = null;
        for (StepRow step : steps) {
            if (step.firstPage() <= page && page <= step.lastPage()
                    && (found == null || width(step) <= width(found))) {
                found = step;
            }
        }
        return found;
    }

    private static int width(StepRow step) {
        return step.lastPage() - step.firstPage();
    }

    /** Ranges by first page as inclusive runs of consecutive pages, each {@code [first, last]}. */
    private static List<int[]> merged(List<PageRangeRow> ranges) {
        List<int[]> runs = new ArrayList<>();
        for (PageRangeRow range : ranges) {
            if (!runs.isEmpty() && runs.getLast()[1] >= range.firstPage() - 1) {
                runs.getLast()[1] = Math.max(runs.getLast()[1], range.lastPage());
            } else {
                runs.add(new int[] {range.firstPage(), range.lastPage()});
            }
        }
        return runs;
    }

    /**
     * A discarded version's steps that have not ended (Story 1.20): their jobs are cancelled and they end, in the
     * caller's transaction. Answers how many.
     */
    public int cancelSteps(UUID versionId) {
        return cancelUnended(versionId, now());
    }

    /**
     * Cancels the jobs of the version's steps that have not ended, and ends those steps (a held step, which has no job,
     * just ends); answers how many.
     */
    private int cancelUnended(UUID versionId, Instant now) {
        List<StepRow> unended = materials.unendedSteps(versionId);
        for (StepRow other : unended) {
            if (other.jobId() == null) {
                materials.endHeldStep(other.id(), now);
            } else {
                jobs.cancel(other.jobId());
                materials.endStep(other.jobId(), now);
            }
        }
        return unended.size();
    }

    /**
     * The version's passages and outline become the report's (Story 1.18): the earlier ones go, so a retried report
     * leaves one set. Each passage gets an id here, its ordinal in reading order, and its text and place encrypted.
     */
    private void replacePassages(JobRef job, UUID versionId, Report report, Instant now) {
        UUID owner = job.ownerId();
        int removed = materials.deletePassages(versionId);
        materials.deleteOutline(versionId);
        List<PassageRow> rows = new ArrayList<>(report.passages().size());
        for (int ordinal = 0; ordinal < report.passages().size(); ordinal++) {
            Report.Passage passage = report.passages().get(ordinal);
            UUID id = Ids.newId();
            rows.add(new PassageRow(id, owner, versionId, ordinal, passage.page(),
                    vault.encryptText(Passages.text(id, owner), passage.text()),
                    vault.encrypt(Passages.place(id, owner), Passages.placeJson(passage.place())), now));
        }
        materials.insertPassages(rows);
        UUID outlineId = Ids.newId();
        materials.insertOutline(new OutlineRow(outlineId, owner, versionId,
                vault.encrypt(Passages.outline(outlineId, owner), Passages.outlineJson(report.outline())), now));
        log.atInfo().addKeyValue("versionId", versionId).addKeyValue("jobId", job.id())
                .addKeyValue("passages", rows.size()).addKeyValue("replaced", removed)
                .addKeyValue("sections", report.outline().size()).log("ingestion.passages_recorded");
    }

    /**
     * The page views of the pages the report names become the report's (Story 1.19): a page's earlier view and its
     * picture go, so a retried report leaves one view per page. Each picture must be an output of this job, refused
     * with {@value #PAGE_VIEW_INVALID} otherwise, and becomes a {@code picture} file of its page; each view's lines or
     * text are encrypted under its own id.
     */
    private void replacePageViews(JobRef job, VersionRow version, Report report, Instant now) {
        UUID owner = job.ownerId();
        List<PageViewRow> replaced = new ArrayList<>();
        for (int[] run : runs(report.pageViews().stream().map(Report.PageView::page).toList())) {
            replaced.addAll(materials.deletePageViews(version.id(), run[0], run[1]));
        }
        List<VersionFileRow> pictures = replaced.stream().filter(PageViewRow::hasPicture)
                .flatMap(view -> materials.deleteVersionFile(version.id(), view.pictureFileId()).stream()).toList();
        removeFilesAfterCommit(pictures);
        int withPicture = 0;
        for (Report.PageView view : report.pageViews()) {
            UUID id = Ids.newId();
            if (view.pictureFileId() != null) {
                adopt(job, version, view.pictureFileId(), VersionFileRow.PICTURE, view.page(), view.page(), now,
                        PAGE_VIEW_INVALID);
                withPicture++;
            }
            materials.insertPageView(new PageViewRow(id, owner, version.id(), view.page(), view.pictureFileId(),
                    view.width(), view.height(), vault.encrypt(PageViews.content(id, owner),
                    PageViews.contentJson(view.content())), now));
        }
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("jobId", job.id())
                .addKeyValue("pageViews", report.pageViews().size()).addKeyValue("pictures", withPicture)
                .addKeyValue("replaced", replaced.size()).log("ingestion.page_views_recorded");
    }

    /** Pages as inclusive runs of consecutive pages, each {@code [first, last]}. */
    private static List<int[]> runs(List<Integer> pages) {
        List<int[]> runs = new ArrayList<>();
        pages.stream().sorted().forEach(page -> {
            if (!runs.isEmpty() && runs.getLast()[1] == page - 1) {
                runs.getLast()[1] = page;
            } else {
                runs.add(new int[] {page, page});
            }
        });
        return runs;
    }

    /**
     * The report's layers and outcomes, each refused when a page of its range already has one (from an earlier report,
     * or from this one: each range is checked after the ones before it are written), then its {@code texts} ranges,
     * each refused unless every page in it has an outcome other than {@code unreadable}.
     */
    private void recordRanges(JobRef job, VersionRow version, Report report, Instant now) {
        for (Report.Range layer : report.layers()) {
            if (materials.layerOverlaps(version.id(), layer.firstPage(), layer.lastPage())) {
                throw new JobResultInvalid("ingestion.layer_repeated");
            }
            materials.insertPageLayer(new PageRangeRow(Ids.newId(), job.ownerId(), version.id(), layer.firstPage(),
                    layer.lastPage(), layer.value(), now));
        }
        for (Report.Range outcome : report.pages()) {
            if (materials.outcomeOverlaps(version.id(), outcome.firstPage(), outcome.lastPage())) {
                throw new JobResultInvalid("ingestion.outcome_repeated");
            }
            materials.insertPageOutcome(new PageRangeRow(Ids.newId(), job.ownerId(), version.id(), outcome.firstPage(),
                    outcome.lastPage(), outcome.value(), now));
        }
        for (Report.Batch text : report.texts()) {
            int next = text.firstPage();
            for (PageRangeRow outcome : materials.outcomesWithin(version.id(), text.firstPage(), text.lastPage())) {
                if (PageRangeRow.UNREADABLE.equals(outcome.value()) || outcome.firstPage() > next) {
                    throw new JobResultInvalid("ingestion.text_pages_unread");
                }
                next = Math.max(next, outcome.lastPage() + 1);
            }
            if (next <= text.lastPage()) {
                throw new JobResultInvalid("ingestion.text_pages_unread");
            }
        }
    }

    /**
     * Once every page has an outcome: when only held steps are left unended, each gets its job (Story 1.18); when no
     * step is left unended, the chain has ended and the version needs review.
     */
    private void endChainIfDone(JobRef job, UUID versionId, Integer total, Instant now) {
        if (total == null) {
            return;
        }
        List<StepRow> unended = materials.unendedSteps(versionId);
        if (unended.stream().anyMatch(step -> !step.held())) {
            return;
        }
        long done = materials.pagesDone(List.of(versionId)).getOrDefault(versionId, 0L);
        if (done != total) {
            return;
        }
        if (unended.isEmpty()) {
            materials.changeVersionState(versionId, NEEDS_REVIEW, now);
            log.atInfo().addKeyValue("versionId", versionId).addKeyValue("jobId", job.id()).addKeyValue("pageTotal", total)
                    .log("ingestion.needs_review");
            return;
        }
        for (StepRow held : unended) {
            JobRef started = jobs.create(KIND, created -> {
                if (!materials.startHeldStep(held.id(), created.id())) {
                    throw new IllegalStateException("ingestion.step_missing");
                }
            });
            log.atInfo().addKeyValue("versionId", versionId).addKeyValue("jobId", job.id())
                    .addKeyValue("newJobId", started.id()).addKeyValue("stage", held.stage())
                    .log("ingestion.held_step_started");
        }
    }

    private void removeFilesAfterCommit(List<VersionFileRow> removed) {
        if (!removed.isEmpty()) {
            afterCommit(() -> removed.forEach(file -> files.deleteFile(file.ownerId(), file.fileId())));
        }
    }

    private VersionFileRow adopt(JobRef job, VersionRow version, UUID fileId, String role, int firstPage, int lastPage,
                                 Instant now) {
        return adopt(job, version, fileId, role, firstPage, lastPage, now, "ingestion.output_unknown");
    }

    /** One of the job's outputs becomes a file of the version; refused with {@code code} when it is not one. */
    private VersionFileRow adopt(JobRef job, VersionRow version, UUID fileId, String role, int firstPage, int lastPage,
                                 Instant now, String code) {
        JobOutput output = jobs.adoptOutput(job, fileId).orElseThrow(() -> new JobResultInvalid(code));
        VersionFileRow row = new VersionFileRow(Ids.newId(), job.ownerId(), version.id(), output.fileId(), role,
                firstPage, lastPage, output.sizeBytes(), output.pieceCount(), now);
        materials.insertVersionFile(row);
        return row;
    }

    /**
     * The raw upload of the version becomes {@code removing}; its pieces and its row go after the commit, and the
     * upload sweep finishes whatever is left (Story 1.20: a discarded version's removal uses it too). From Story 1.21,
     * every complete image of a set.
     */
    void removeRawUpload(UUID owner, UUID versionId, Instant now) {
        List<UploadRow> removed = uploads.joined().completeOfVersion(owner, versionId).stream()
                .filter(upload -> uploads.joined().removeComplete(upload.id(), now)).toList();
        if (removed.isEmpty()) {
            return;
        }
        afterCommit(() -> removed.forEach(row -> {
            files.deleteFile(row.ownerId(), row.id());
            uploads.separately(store -> store.deleteRemoving(row.id()));
            log.atInfo().addKeyValue("ownerId", row.ownerId()).addKeyValue("uploadId", row.id()).log("upload.removed");
        }));
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
     * {@code original} ({@code fileId}), {@code batches} and, from Story 1.16, {@code texts} ({@code fileId},
     * {@code firstPage}, {@code lastPage}), {@code layers} ({@code firstPage}, {@code lastPage}, {@code layer}),
     * {@code pages} ({@code firstPage}, {@code lastPage}, {@code outcome}) and {@code next} ({@code stage},
     * {@code firstPage}, {@code lastPage}, {@code input}: a file id of the version, or from Story 1.18
     * {@value #TEXTS}). Every range is inclusive. From Story 1.18, {@code passages} ({@code page}, {@code sectionPath},
     * {@code text}, and {@code boxes} or {@code range}) in reading order and {@code outline} ({@code level},
     * {@code title}, {@code page}): when the report gives either, both are lists (an absent one empty); when it gives
     * neither, both are null. From Story 1.19, {@code pageViews}: each {@code page}, which this report's
     * {@code pages} must give an outcome and no other view names, with either {@code picture} ({@code fileId},
     * {@code width}, {@code height}) and {@code lines} ({@code text}, {@code box}), or a text page's {@code text}.
     */
    record Report(String pipelineVersion, Integer pageTotal, UUID original, List<Batch> batches, List<Batch> texts,
                  List<Range> layers, List<Range> pages, List<Next> next, List<Passage> passages,
                  List<Passages.Entry> outline, List<PageView> pageViews) {

        /** A page view: a picture page's file, size and lines, or a text page's text (no picture). */
        record PageView(int page, UUID pictureFileId, Integer width, Integer height, PageViews.Content content) {
            @Override
            public String toString() {
                return "PageView[page=" + page + "]";
            }
        }

        record Batch(UUID fileId, int firstPage, int lastPage) {
        }

        /** A range of pages and its layer or its outcome. */
        record Range(int firstPage, int lastPage, String value) {
        }

        /** A next step over a file of the version ({@code input}), or over every text file ({@code texts}). */
        record Next(String stage, Integer firstPage, Integer lastPage, UUID input, boolean texts) {
        }

        /** A passage: its page, its text, and its place (section path, and boxes or range). */
        record Passage(int page, String text, Passages.Place place) {
            @Override
            public String toString() {
                return "Passage[page=" + page + "]";
            }
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
                JsonNode input = node.path("input");
                boolean texts = input.isString() && TEXTS.equals(input.asString());
                next.add(new Next(stage.asString(), first, last, texts ? null : fileId(input), texts));
            }
            List<Passage> passages = null;
            List<Passages.Entry> outline = null;
            if (present(result.path("passages")) || present(result.path("outline"))) {
                passages = passages(result.path("passages"));
                outline = outline(result.path("outline"));
            }
            List<Range> pages = ranges(result.path("pages"), "outcome", OUTCOMES);
            return new Report(version.asString(), total, original, files(result.path("batches")),
                    files(result.path("texts")), ranges(result.path("layers"), "layer", LAYERS), pages,
                    List.copyOf(next), passages, outline, pageViews(result.path("pageViews"), pages));
        }

        /**
         * The report's page views (Story 1.19), each on a page this report gives an outcome, no page twice: a picture
         * of 1 to {@value #MAX_SIDE} pixels a side with at most {@value #MAX_LINES} lines (each 1 to
         * {@value #MAX_LINE} code points, its box four fractions), or a text of at most {@value #MAX_PAGE_TEXT} code
         * points.
         */
        private static List<PageView> pageViews(JsonNode node, List<Range> pages) {
            if (node.isMissingNode() || node.isNull()) {
                return List.of();
            }
            if (!node.isArray()) {
                throw new JobResultInvalid(PAGE_VIEW_INVALID);
            }
            List<PageView> found = new ArrayList<>(node.size());
            Set<Integer> seen = new HashSet<>();
            for (JsonNode item : node) {
                JsonNode pageNode = item.path("page");
                if (!item.isObject() || !pageNode.isIntegralNumber() || !pageNode.canConvertToInt()) {
                    throw new JobResultInvalid(PAGE_VIEW_INVALID);
                }
                int page = pageNode.intValue();
                if (!seen.add(page) || pages.stream().noneMatch(range -> range.firstPage() <= page
                        && page <= range.lastPage())) {
                    throw new JobResultInvalid(PAGE_VIEW_INVALID);
                }
                boolean picture = present(item.path("picture"));
                if (picture == present(item.path("text"))) {
                    throw new JobResultInvalid(PAGE_VIEW_INVALID);
                }
                found.add(picture ? picturePage(page, item) : textPage(page, item));
            }
            return List.copyOf(found);
        }

        private static PageView textPage(int page, JsonNode item) {
            JsonNode text = item.path("text");
            if (present(item.path("lines")) || !text.isString() || !within(text.asString(), 0, MAX_PAGE_TEXT)) {
                throw new JobResultInvalid(PAGE_VIEW_INVALID);
            }
            return new PageView(page, null, null, null, new PageViews.Content(null, text.asString()));
        }

        private static PageView picturePage(int page, JsonNode item) {
            JsonNode picture = item.path("picture");
            JsonNode fileId = picture.path("fileId");
            JsonNode lines = item.path("lines");
            if (!picture.isObject() || !fileId.isString() || !UUID_TEXT.matcher(fileId.asString()).matches()
                    || !lines.isArray() || lines.size() > MAX_LINES) {
                throw new JobResultInvalid(PAGE_VIEW_INVALID);
            }
            List<PageViews.Line> found = new ArrayList<>(lines.size());
            for (JsonNode line : lines) {
                JsonNode text = line.path("text");
                JsonNode box = line.path("box");
                if (!line.isObject() || !text.isString() || !within(text.asString(), 1, MAX_LINE) || !box.isArray()
                        || box.size() != 4) {
                    throw new JobResultInvalid(PAGE_VIEW_INVALID);
                }
                List<Double> sides = new ArrayList<>(4);
                for (JsonNode side : box) {
                    if (!fraction(side)) {
                        throw new JobResultInvalid(PAGE_VIEW_INVALID);
                    }
                    sides.add(side.asDouble());
                }
                found.add(new PageViews.Line(text.asString(), List.copyOf(sides)));
            }
            return new PageView(page, UUID.fromString(fileId.asString()), side(picture.path("width")),
                    side(picture.path("height")), new PageViews.Content(List.copyOf(found), null));
        }

        private static int side(JsonNode node) {
            if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1
                    || node.intValue() > MAX_SIDE) {
                throw new JobResultInvalid(PAGE_VIEW_INVALID);
            }
            return node.intValue();
        }

        private static boolean present(JsonNode node) {
            return !node.isMissingNode() && !node.isNull();
        }

        private static List<Passage> passages(JsonNode node) {
            List<Passage> found = new ArrayList<>();
            for (JsonNode item : array(node)) {
                JsonNode text = item.path("text");
                if (!text.isString() || !within(text.asString(), 1, MAX_PASSAGE)) {
                    throw new JobResultInvalid(PASSAGE_INVALID);
                }
                List<String> path = new ArrayList<>();
                for (JsonNode title : values(item.path("sectionPath"), MAX_DEPTH)) {
                    path.add(title(title, PASSAGE_INVALID));
                }
                boolean boxes = present(item.path("boxes"));
                if (boxes == present(item.path("range"))) {
                    throw new JobResultInvalid(PASSAGE_INVALID);
                }
                Passages.Place place = boxes ? new Passages.Place(List.copyOf(path), boxes(item.path("boxes")), null)
                        : new Passages.Place(List.copyOf(path), null, range(item.path("range")));
                found.add(new Passage(page(item.path("page")), text.asString(), place));
            }
            return List.copyOf(found);
        }

        /** A passage's boxes on its page: each four fractions of the page, {@code [left, top, right, bottom]}. */
        private static List<List<Double>> boxes(JsonNode node) {
            List<List<Double>> found = new ArrayList<>();
            for (JsonNode box : values(node, MAX_PASSAGE)) {
                List<JsonNode> sides = values(box, 4);
                if (sides.size() != 4 || !sides.stream().allMatch(Report::fraction)) {
                    throw new JobResultInvalid(PASSAGE_INVALID);
                }
                found.add(sides.stream().map(JsonNode::asDouble).toList());
            }
            return List.copyOf(found);
        }

        private static boolean fraction(JsonNode side) {
            return side.isNumber() && Double.isFinite(side.asDouble()) && side.asDouble() >= 0 && side.asDouble() <= 1;
        }

        /** A passage's range in its page's text, {@code [start, end)} in code points, never empty. */
        private static List<Integer> range(JsonNode node) {
            List<JsonNode> ends = values(node, 2);
            if (ends.size() != 2 || !ends.stream().allMatch(end -> end.isIntegralNumber() && end.canConvertToInt())
                    || ends.get(0).intValue() < 0 || ends.get(1).intValue() <= ends.get(0).intValue()) {
                throw new JobResultInvalid(PASSAGE_INVALID);
            }
            return List.of(ends.get(0).intValue(), ends.get(1).intValue());
        }

        private static List<Passages.Entry> outline(JsonNode node) {
            List<Passages.Entry> found = new ArrayList<>();
            for (JsonNode item : array(node)) {
                JsonNode level = item.path("level");
                if (!level.isIntegralNumber() || !level.canConvertToInt() || level.intValue() < 0
                        || level.intValue() > MAX_LEVEL) {
                    throw new JobResultInvalid(OUTLINE_INVALID);
                }
                found.add(new Passages.Entry(level.intValue(), title(item.path("title"), OUTLINE_INVALID),
                        page(item.path("page"))));
            }
            return List.copyOf(found);
        }

        private static String title(JsonNode node, String code) {
            if (!node.isString() || !within(node.asString(), 1, MAX_TITLE)) {
                throw new JobResultInvalid(code);
            }
            return node.asString();
        }

        /** Whether a text has {@code min} to {@code max} code points (counted only when it may fit). */
        private static boolean within(String text, int min, int max) {
            if (text.length() > 2 * max) {
                return false;
            }
            int points = text.codePointCount(0, text.length());
            return points >= min && points <= max;
        }

        /** The items of an array of at most {@code max} items; anything else is an invalid passage. */
        private static List<JsonNode> values(JsonNode node, int max) {
            if (!node.isArray() || node.size() > max) {
                throw new JobResultInvalid(PASSAGE_INVALID);
            }
            List<JsonNode> items = new ArrayList<>(node.size());
            node.forEach(items::add);
            return items;
        }

        private static List<Batch> files(JsonNode node) {
            List<Batch> found = new ArrayList<>();
            for (JsonNode item : array(node)) {
                found.add(new Batch(fileId(item.path("fileId")), page(item.path("firstPage")), page(item.path("lastPage"))));
            }
            return List.copyOf(found);
        }

        private static List<Range> ranges(JsonNode node, String field, Set<String> values) {
            List<Range> found = new ArrayList<>();
            for (JsonNode item : array(node)) {
                JsonNode value = item.path(field);
                if (!value.isString() || !values.contains(value.asString())) {
                    throw new JobResultInvalid("ingestion.range_invalid");
                }
                found.add(new Range(page(item.path("firstPage")), page(item.path("lastPage")), value.asString()));
            }
            return List.copyOf(found);
        }

        /** Whether the report names a page or a file of pages, which needs a page total. */
        boolean namesPages() {
            return original != null || !batches.isEmpty() || !texts.isEmpty() || !layers.isEmpty() || !pages.isEmpty()
                    || !next.isEmpty() || carriesPassages() && (!passages.isEmpty() || !outline.isEmpty())
                    || !pageViews.isEmpty();
        }

        /** Whether the report gives the version's passages and outline (Story 1.18), which replace its earlier ones. */
        boolean carriesPassages() {
            return passages != null;
        }

        /** Every page range lies within 1 to {@code total}, first before last. */
        void checkPages(int total) {
            for (Batch batch : batches) {
                range(batch.firstPage(), batch.lastPage(), total);
            }
            for (Batch text : texts) {
                range(text.firstPage(), text.lastPage(), total);
            }
            for (Range layer : layers) {
                range(layer.firstPage(), layer.lastPage(), total);
            }
            for (Range outcome : pages) {
                range(outcome.firstPage(), outcome.lastPage(), total);
            }
            for (Next step : next) {
                if (step.firstPage() != null) {
                    range(step.firstPage(), step.lastPage(), total);
                }
            }
            if (carriesPassages()) {
                for (Passage passage : passages) {
                    range(passage.page(), passage.page(), total);
                }
                for (Passages.Entry entry : outline) {
                    range(entry.page(), entry.page(), total);
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
