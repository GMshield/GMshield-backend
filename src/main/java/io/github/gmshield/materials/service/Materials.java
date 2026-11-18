package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.NewestVersion;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.PageViewRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The owner's materials as the interface shows them (Story 1.14): each with its name, decrypted, and the state of its
 * newest version, which is the material's state; while that version is {@code uploading}, the upload's progress too;
 * from Story 1.15, its pages once the page total is known and the failure code of a failed version; from Story 1.16,
 * the pages done are the pages its chain gave an outcome; from Story 1.17, the pages of each layer and how many of them
 * have an outcome, and the pages of each outcome, all derived here from the recorded ranges; and {@code reading}, the
 * layer of the pages of the step whose job is offered or running, from its page range and the layer ranges (a held step,
 * or one over every text file of its version, reads no page: Story 1.18). The backend never reads a stage name and
 * names no phase: the frontend chooses the words. From Story 1.18, a passage with its material, section path, page
 * and text ({@link #passage}), and a version's outline ({@link #outline}), decrypted, for their owner only. From Story
 * 1.19, one page of a material on a {@link Ground} ({@link #page}): its picture's size, its lines or text, and its
 * passages' places, and the picture itself ({@link #picture}), streamed piece by piece. From Story 1.20, each
 * material's {@code actions} (those of {@value #ACCEPT}, {@value #REPROCESS}, {@value #RETRY} and {@value #DISCARD}
 * its newest version allows now: {@link #actions}, the one place of that rule), the quality report of a version that
 * needs review or is ready ({@link #report}), each page's outcome, an unreadable page with no view answered as one with
 * nothing on it, and only passages of a {@code ready} version.
 * Everything acts for the owner of the work ({@link OwnerContext}): another owner's material reads like an unknown one.
 * Nothing here logs a name.
 */
@Service
public class Materials {

    /** Every state a version may be in, as the tables write them. */
    public static final Set<String> STATES = Set.of("uploading", "waiting", "processing", "needs_review", "ready",
            "superseded", "failed", "discarded");
    static final String FAILED = "failed";
    static final String READY = "ready";
    static final String SUPERSEDED = "superseded";
    /** The GM's actions on a material's newest version (Story 1.20), in the order the interface shows them. */
    public static final String ACCEPT = "accept";
    public static final String REPROCESS = "reprocess";
    public static final String RETRY = "retry";
    public static final String DISCARD = "discard";
    public static final List<String> ACTIONS = List.of(ACCEPT, REPROCESS, RETRY, DISCARD);
    /** The report's warnings (Story 1.20): facts the backend derives, each with a count. */
    public static final String PAGES_UNREADABLE = "report.pages_unreadable";
    public static final String PAGES_UNOPENED = "report.pages_unopened";
    public static final String OUTLINE_EMPTY = "report.outline_empty";
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private final MaterialStore materials;
    private final UploadStorage uploads;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final JobsInProgress inProgress;
    private final FileStore files;
    private final IngestionSettings settings;

    /** Those of these jobs of the bound owner that are offered or running ({@link Jobs#inProgress}). */
    @FunctionalInterface
    interface JobsInProgress {
        Set<UUID> of(Collection<UUID> jobIds);
    }

    @Autowired
    Materials(MaterialStore materials, UploadStorage uploads, Vault vault, FileStore files, Jobs jobs,
              IngestionSettings settings, ObjectProvider<PlatformTransactionManager> transactions) {
        this(materials, uploads, vault, files, jobs::inProgress, settings, transactions);
    }

    Materials(MaterialStore materials, UploadStorage uploads, Vault vault, FileStore files, JobsInProgress inProgress,
              IngestionSettings settings, ObjectProvider<PlatformTransactionManager> transactions) {
        this.settings = settings;
        this.materials = materials;
        this.uploads = uploads;
        this.vault = vault;
        this.files = files;
        this.inProgress = inProgress;
        this.transactions = transactions;
    }

    /** An unfinished upload: its id, the bytes verified and stored, its length and when it expires. */
    public record Upload(UUID id, long offset, long length, Instant expiresAt) {
    }

    /**
     * An image set still uploading (Story 1.21): its id (its version's) and its images in order. Each image has its
     * place, its upload, its state ({@code open}, {@code complete} or {@code failed}), the bytes verified of its length,
     * its expiry, and, while it is not complete, its file name (to resume it or name it); a failed one has its reason.
     */
    public record ImageSet(UUID id, List<SetImage> images) {
    }

    /** One image of a set still uploading. {@code toString} names no file. */
    public record SetImage(int position, UUID uploadId, String name, String state, long offset, long length,
                           Instant expiresAt, String failure) {
        @Override
        public String toString() {
            return "SetImage[position=" + position + ", state=" + state + "]";
        }
    }

    /**
     * Pages of the version's page total that have an outcome (Stories 1.15 and 1.16); from Story 1.17, the pages with a
     * text layer and with an image only ({@code text}, {@code image}: those of them with an outcome, of all of them),
     * the pages read directly, read by OCR and unreadable, and {@code reading}: {@value PageRangeRow#TEXT} or
     * {@value PageRangeRow#IMAGE} when the pages of the step in progress all have that layer, else null.
     */
    public record Pages(int done, int total, Count text, Count image, int readDirectly, int readByOcr, int unreadable,
                        String reading) {

        /** The pages of the total alone: before any layer or outcome is recorded. */
        public Pages(int done, int total) {
            this(done, total, Count.NONE, Count.NONE, 0, 0, 0, null);
        }

        /** The counts with no step in progress. */
        public Pages(int done, int total, Count text, Count image, int readDirectly, int readByOcr, int unreadable) {
            this(done, total, text, image, readDirectly, readByOcr, unreadable, null);
        }
    }

    /** Pages of a kind that have an outcome, of all the pages of that kind. */
    public record Count(int done, int total) {
        public static final Count NONE = new Count(0, 0);
    }

    /**
     * A material: {@code kind} may be null, {@code upload} is there only while the state is {@code uploading},
     * {@code pages} once the page total is known, and {@code failure} (a code) only when the state is {@code failed};
     * {@code actions}, the GM's actions its newest version allows now (Story 1.20), in {@link #ACTIONS} order; from
     * Story 1.21, {@code imageSet} only while an image set is uploading (then {@code upload} is null).
     */
    public record Material(UUID id, String name, String state, String kind, Upload upload, Pages pages,
                           String failure, List<String> actions, ImageSet imageSet) {
        @Override
        public String toString() {
            return "Material[id=" + id + ", state=" + state + "]";
        }
    }

    /**
     * A passage of a material's version (Story 1.18): its material (id and name), version, section path (outermost
     * first), page and text. {@code toString} names ids and the page only.
     */
    public record Passage(UUID id, UUID materialId, String materialName, UUID versionId, List<String> sectionPath,
                          int page, String text) {
        @Override
        public String toString() {
            return "Passage[id=" + id + ", versionId=" + versionId + ", page=" + page + "]";
        }
    }

    /** One section of a version's outline (Story 1.18): its heading's level and title, and the page it starts on. */
    public record Section(int level, String title, int page) {
        @Override
        public String toString() {
            return "Section[level=" + level + ", page=" + page + "]";
        }
    }

    /**
     * The ground a page is served on (Story 1.19, AD-11): {@link #REVIEW}, the owner reviewing a version, serves the
     * material's current version while it is {@code needs_review} or {@code ready}. Epics 2 and 3 add their grounds.
     */
    public enum Ground {
        REVIEW("review", Set.of(IngestionSteps.NEEDS_REVIEW, "ready"));

        private final String word;
        private final Set<String> states;

        Ground(String word, Set<String> states) {
            this.word = word;
            this.states = states;
        }

        /** The ground of this word; empty for a missing or unknown one. */
        public static Optional<Ground> of(String word) {
            for (Ground ground : values()) {
                if (ground.word.equals(word)) {
                    return Optional.of(ground);
                }
            }
            return Optional.empty();
        }

        boolean serves(VersionRow version) {
            return states.contains(version.state());
        }
    }

    /** A picture's size in pixels. */
    public record PictureSize(int width, int height) {
    }

    /** A text line of a picture page and its box, {@code [left, top, right, bottom]} as fractions of the picture. */
    public record Line(String text, List<Double> box) {
        @Override
        public String toString() {
            return "Line[box=" + box + "]";
        }
    }

    /** A passage's place on its page: its boxes (a picture page), or its range in the page's text (a text page). */
    public record PagePassage(UUID id, List<List<Double>> boxes, List<Integer> range) {
    }

    /**
     * One page of a version (Story 1.19): its number and the version's page total, its picture's size and its lines
     * (a PDF page), or its text (a text page: {@code picture} and {@code text} null otherwise, {@code lines} empty),
     * and the places of the passages on it, in reading order; from Story 1.20 its outcome (null when it has none). An
     * unreadable page with no view has no picture, no lines, no text and no passages. {@code toString} names numbers
     * only.
     */
    public record VersionPage(int page, int pageTotal, PictureSize picture, List<Line> lines, String text,
                              List<PagePassage> passages, String outcome) {
        @Override
        public String toString() {
            return "VersionPage[page=" + page + ", pageTotal=" + pageTotal + "]";
        }
    }

    /** A page's picture as the file store holds it: WebP, streamed one decrypted piece at a time. */
    public final class Picture {
        private final UUID owner;
        private final UUID fileId;
        private final long size;
        private final int pieces;

        Picture(VersionFileRow file) {
            this.owner = file.ownerId();
            this.fileId = file.fileId();
            this.size = file.sizeBytes();
            this.pieces = file.pieceCount();
        }

        public long size() {
            return size;
        }

        public void writeTo(OutputStream out) throws IOException {
            for (int index = 0; index < pieces; index++) {
                out.write(files.readPiece(owner, fileId, index));
            }
        }

        @Override
        public String toString() {
            return "Picture[fileId=" + fileId + ", size=" + size + "]";
        }
    }

    /** A run of pages, {@code firstPage} to {@code lastPage} inclusive. */
    public record PageRun(int firstPage, int lastPage) {
    }

    /** A fact of the quality report (Story 1.20): its code ({@link #PAGES_UNREADABLE}, ...) and its count. */
    public record Warning(String code, int count) {
    }

    /**
     * The quality report of a version (Story 1.20): its outline in reading order, its unreadable pages as runs, and
     * its warnings. The counts are the material's {@link Pages}.
     */
    public record Report(List<Section> outline, List<PageRun> unreadable, List<Warning> warnings) {
        @Override
        public String toString() {
            return "Report[sections=" + outline.size() + ", unreadable=" + unreadable.size() + "]";
        }
    }

    /** One page of materials, and the cursor of the next one (null when this is the last). */
    public record Page(List<Material> items, UUID nextCursor) {
    }

    /**
     * The owner's materials whose newest version is in one of {@code states}, newest first, after the material
     * {@code cursor} when it is given, at most {@code limit} (1 to {@value #MAX_LIMIT}).
     */
    public Page list(Collection<String> states, UUID cursor, int limit) {
        if (states.isEmpty() || !STATES.containsAll(states) || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("materials.list_invalid");
        }
        return reading(() -> {
            List<NewestVersion> found = materials.newestIn(Set.copyOf(states), cursor, limit + 1);
            List<NewestVersion> page = found.size() > limit ? found.subList(0, limit) : found;
            UUID next = found.size() > limit ? page.getLast().material().id() : null;
            return new Page(views(page), next);
        });
    }

    /** The owner's material with this id; empty for an unknown id or another owner's. */
    public Optional<Material> find(UUID id) {
        return reading(() -> materials.newest(id).map(found -> views(List.of(found)).getFirst()));
    }

    /**
     * The owner's passage with this id; empty for an unknown id or another owner's, and from Story 1.20 for a passage of
     * a version that is not {@code ready}: nothing of a version the GM has not accepted reaches a later feature. From
     * Story 2.2 it is not public: another module reads passages for a campaign only through {@link ScopeReads}.
     */
    Optional<Passage> passage(UUID id) {
        return reading(() -> materials.findPassage(id).flatMap(row -> materials.findVersion(row.versionId())
                .filter(version -> READY.equals(version.state()))
                .flatMap(version -> materials.findMaterial(version.materialId()))
                .map(material -> passage(row, material))));
    }

    private Passage passage(PassageRow row, MaterialRow material) {
        Passages.Place place = Passages.place(vault.decrypt(Passages.place(row.id(), row.ownerId()), row.place()));
        return new Passage(row.id(), material.id(),
                vault.decryptText(new Binding(Uploads.MATERIAL, Uploads.FIELD_NAME, material.id(), material.ownerId()),
                        material.name()),
                row.versionId(), place.sectionPath(), row.page(),
                vault.decryptText(Passages.text(row.id(), row.ownerId()), row.text()));
    }

    /**
     * The outline of the owner's version with this id, its sections in reading order; empty for an unknown version,
     * another owner's, or one whose passages are not recorded yet.
     */
    public Optional<List<Section>> outline(UUID versionId) {
        return reading(() -> materials.findOutline(versionId).map(row -> Passages.outline(
                        vault.decrypt(Passages.outline(row.id(), row.ownerId()), row.outline())).stream()
                .map(entry -> new Section(entry.level(), entry.title(), entry.page())).toList()));
    }

    /**
     * The quality report of the owner's material (Story 1.20): its newest version's, while that version needs review
     * or is ready; empty for an unknown material, another owner's, or a version in any other state. The warnings:
     * {@value #PAGES_UNREADABLE} (the unreadable pages), {@value #PAGES_UNOPENED} (those of them with no page view: their
     * reading crashed) and {@value #OUTLINE_EMPTY} (no section was found), each only when it holds.
     */
    public Optional<Report> report(UUID materialId) {
        return reading(() -> materials.newest(materialId).map(NewestVersion::version)
                .filter(version -> REVIEWED.contains(version.state())).map(version -> {
                    List<Section> outline = materials.findOutline(version.id()).map(row -> Passages.outline(
                                    vault.decrypt(Passages.outline(row.id(), row.ownerId()), row.outline())).stream()
                            .map(entry -> new Section(entry.level(), entry.title(), entry.page())).toList())
                            .orElse(List.of());
                    List<PageRun> unreadable = runs(materials.pageOutcomes(List.of(version.id())).stream()
                            .filter(range -> PageRangeRow.UNREADABLE.equals(range.value()))
                            .sorted(Comparator.comparingInt(PageRangeRow::firstPage)).toList());
                    int pages = unreadable.stream().mapToInt(run -> run.lastPage() - run.firstPage() + 1).sum();
                    int opened = unreadable.stream().mapToInt(run -> materials.pagesWithView(version.id(),
                            run.firstPage(), run.lastPage()).size()).sum();
                    List<Warning> warnings = new java.util.ArrayList<>();
                    if (pages > 0) {
                        warnings.add(new Warning(PAGES_UNREADABLE, pages));
                    }
                    if (pages > opened) {
                        warnings.add(new Warning(PAGES_UNOPENED, pages - opened));
                    }
                    if (outline.isEmpty()) {
                        warnings.add(new Warning(OUTLINE_EMPTY, 0));
                    }
                    return new Report(outline, unreadable, List.copyOf(warnings));
                }));
    }

    /** The states whose report and pages the GM reads (Story 1.20). */
    static final Set<String> REVIEWED = Set.of(IngestionSteps.NEEDS_REVIEW, READY);

    /** Ranges by first page as runs of consecutive pages: adjacent or overlapping ranges become one. */
    static List<PageRun> runs(List<PageRangeRow> ranges) {
        List<PageRun> runs = new java.util.ArrayList<>();
        for (PageRangeRow range : ranges) {
            PageRun last = runs.isEmpty() ? null : runs.getLast();
            if (last != null && last.lastPage() >= range.firstPage() - 1) {
                runs.set(runs.size() - 1, new PageRun(last.firstPage(), Math.max(last.lastPage(), range.lastPage())));
            } else {
                runs.add(new PageRun(range.firstPage(), range.lastPage()));
            }
        }
        return List.copyOf(runs);
    }

    /**
     * Page {@code page} of the owner's material on {@code ground}: empty for an unknown material or another owner's,
     * a current version the ground does not serve, a page outside 1 to the page total, or a page with no page view
     * unless it is unreadable (its reading crashed: Story 1.20), which is a page with nothing on it.
     */
    public Optional<VersionPage> page(UUID materialId, int page, Ground ground) {
        return reading(() -> served(materialId, page, ground).flatMap(version -> {
            String outcome = materials.outcomesWithin(version.id(), page, page).stream().findFirst()
                    .map(PageRangeRow::value).orElse(null);
            return materials.findPageView(version.id(), page).map(view -> versionPage(version, view, outcome))
                    .or(() -> PageRangeRow.UNREADABLE.equals(outcome) ? Optional.of(new VersionPage(page,
                            version.pageTotal(), null, List.of(), null, List.of(), outcome)) : Optional.empty());
        }));
    }

    /** The picture of that page, as {@link #page} finds it; empty also for a page with no picture. */
    public Optional<Picture> picture(UUID materialId, int page, Ground ground) {
        return reading(() -> served(materialId, page, ground).flatMap(version -> materials
                .findPageView(version.id(), page).filter(PageViewRow::hasPicture)
                .flatMap(view -> materials.versionFile(version.id(), view.pictureFileId()))
                .filter(file -> VersionFileRow.PICTURE.equals(file.role())).map(Picture::new)));
    }

    /** The material's current version when the ground serves it and the page is one of its pages. */
    private Optional<VersionRow> served(UUID materialId, int page, Ground ground) {
        return materials.newest(materialId).map(NewestVersion::version).filter(ground::serves)
                .filter(version -> version.pageTotal() != null && page >= 1 && page <= version.pageTotal());
    }

    private VersionPage versionPage(VersionRow version, PageViewRow view, String outcome) {
        PageViews.Content content = PageViews.content(vault.decrypt(PageViews.content(view.id(), view.ownerId()),
                view.content()));
        List<PagePassage> passages = materials.passagesOnPage(version.id(), view.page()).stream().map(row -> {
            Passages.Place place = Passages.place(vault.decrypt(Passages.place(row.id(), row.ownerId()), row.place()));
            return new PagePassage(row.id(), place.boxes(), place.range());
        }).toList();
        List<Line> lines = content.lines().stream().map(line -> new Line(line.text(), line.box())).toList();
        return new VersionPage(view.page(), version.pageTotal(),
                view.hasPicture() ? new PictureSize(view.width(), view.height()) : null, lines, content.text(),
                passages, outcome);
    }

    private List<Material> views(List<NewestVersion> rows) {
        UUID owner = OwnerContext.require();
        List<UUID> uploading = rows.stream().map(NewestVersion::version)
                .filter(version -> Uploads.UPLOADING.equals(version.state())).map(version -> version.id()).toList();
        // A plain upload's one open row; a set's members are grouped apart, by version (Story 1.21).
        Map<UUID, UploadRow> open = uploads.joined().openForVersions(owner, uploading).stream()
                .filter(row -> !row.member())
                .collect(Collectors.toMap(UploadRow::versionId, Function.identity(), (first, second) -> first));
        Map<UUID, List<UploadRow>> sets = uploads.joined().membersOf(owner, uploading).stream()
                .collect(Collectors.groupingBy(UploadRow::versionId));
        List<UUID> counted = rows.stream().map(NewestVersion::version).filter(version -> version.pageTotal() != null)
                .map(version -> version.id()).toList();
        // One read per kind of range for all the listed versions; the counts are derived here.
        Map<UUID, List<PageRangeRow>> layers = byVersion(materials.pageLayers(counted));
        Map<UUID, List<PageRangeRow>> outcomes = byVersion(materials.pageOutcomes(counted));
        // The step in progress: the version's unended steps, then which of their jobs are offered or running.
        // A held step has no job, and one over every text file reads no page: neither is ever in progress here.
        List<StepRow> unended = counted.isEmpty() ? List.of() : materials.unendedSteps(counted).stream()
                .filter(step -> step.jobId() != null && !step.inputTexts()).toList();
        Set<UUID> running = unended.isEmpty() ? Set.of()
                : inProgress.of(unended.stream().map(StepRow::jobId).toList());
        Map<UUID, List<StepRow>> current = unended.stream().filter(step -> running.contains(step.jobId()))
                .collect(Collectors.groupingBy(StepRow::versionId));
        return rows.stream().map(row -> {
            UploadRow upload = Uploads.UPLOADING.equals(row.version().state()) ? open.get(row.version().id()) : null;
            Integer total = row.version().pageTotal();
            Pages pages = total == null ? null : pages(total, layers.getOrDefault(row.version().id(), List.of()),
                    outcomes.getOrDefault(row.version().id(), List.of()),
                    current.getOrDefault(row.version().id(), List.of()));
            return new Material(row.material().id(),
                    vault.decryptText(new Binding(Uploads.MATERIAL, Uploads.FIELD_NAME, row.material().id(),
                            row.material().ownerId()), row.material().name()),
                    row.version().state(), row.material().kind(),
                    upload == null ? null : new Upload(upload.id(), upload.offset(), upload.length(), upload.expiresAt()),
                    pages, FAILED.equals(row.version().state()) ? row.version().failureCode() : null,
                    actions(row.version().state(), pages == null ? 0 : pages.unreadable(),
                            IngestionSteps.retryCheck(materials, uploads, settings,
                                    row.version()) == IngestionSteps.RetryCheck.ALLOWED),
                    imageSet(row.version(), sets.get(row.version().id())));
        }).toList();
    }

    /** A version's image set while it uploads (Story 1.21); null for any other version. */
    private ImageSet imageSet(VersionRow version, List<UploadRow> members) {
        if (members == null || members.isEmpty() || !Uploads.UPLOADING.equals(version.state())) {
            return null;
        }
        return new ImageSet(version.id(), members.stream().map(member -> new SetImage(member.position(),
                member.id(), UploadRow.COMPLETE.equals(member.state()) || member.name() == null ? null
                : vault.decryptText(Uploads.memberName(member.id(), member.ownerId()), member.name()),
                member.state(), member.offset(), member.length(), member.expiresAt(), member.failureCode())).toList());
    }

    /**
     * The GM's actions a version allows now (Story 1.20), in {@link #ACTIONS} order: {@value #ACCEPT} while it needs
     * review, {@value #REPROCESS} while it needs review with an unreadable page, {@value #RETRY} while it is failed and
     * would be retried ({@code retryable}: {@link IngestionSteps#retryCheck}), and {@value #DISCARD}
     * while it needs review or is failed. A {@code ready} version is never reprocessed nor discarded in place (AD-15).
     */
    static List<String> actions(String state, int unreadable, boolean retryable) {
        if (IngestionSteps.NEEDS_REVIEW.equals(state)) {
            return unreadable > 0 ? List.of(ACCEPT, REPROCESS, DISCARD) : List.of(ACCEPT, DISCARD);
        }
        if (FAILED.equals(state)) {
            return retryable ? List.of(RETRY, DISCARD) : List.of(DISCARD);
        }
        return List.of();
    }

    private static Map<UUID, List<PageRangeRow>> byVersion(List<PageRangeRow> ranges) {
        return ranges.stream().collect(Collectors.groupingBy(PageRangeRow::versionId, Collectors.collectingAndThen(
                Collectors.toList(), found -> found.stream().sorted(Comparator.comparingInt(PageRangeRow::firstPage))
                        .toList())));
    }

    /**
     * A version's pages from its layer and outcome ranges, each list by first page with no page twice: the pages of
     * each outcome, and for each layer its pages and those of them that any outcome covers.
     */
    static Pages pages(int total, List<PageRangeRow> layers, List<PageRangeRow> outcomes) {
        return pages(total, layers, outcomes, List.of());
    }

    /** {@link #pages(int, List, List)}, with the version's steps whose jobs are offered or running. */
    static Pages pages(int total, List<PageRangeRow> layers, List<PageRangeRow> outcomes, List<StepRow> current) {
        List<PageRangeRow> text = valued(layers, PageRangeRow.TEXT);
        List<PageRangeRow> image = valued(layers, PageRangeRow.IMAGE);
        return new Pages(sum(outcomes), total, new Count(covered(text, outcomes), sum(text)),
                new Count(covered(image, outcomes), sum(image)), sum(valued(outcomes, PageRangeRow.READ_DIRECTLY)),
                sum(valued(outcomes, PageRangeRow.READ_BY_OCR)), sum(valued(outcomes, PageRangeRow.UNREADABLE)),
                reading(layers, current));
    }

    /**
     * The one layer of every page of the steps in progress: null when there is none, when one has no pages (clean),
     * when a page has no layer yet (triage), or when their layers differ.
     */
    static String reading(List<PageRangeRow> layers, List<StepRow> current) {
        if (current.isEmpty()) {
            return null;
        }
        Set<String> found = new HashSet<>();
        for (StepRow step : current) {
            if (step.firstPage() == null || step.lastPage() == null) {
                return null;
            }
            int first = step.firstPage();
            int last = step.lastPage();
            int covering = 0;
            for (PageRangeRow layer : layers) {
                int from = Math.max(first, layer.firstPage());
                int to = Math.min(last, layer.lastPage());
                if (from <= to) {
                    covering += to - from + 1;
                    found.add(layer.value());
                }
            }
            if (covering < last - first + 1) {
                return null;
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static List<PageRangeRow> valued(List<PageRangeRow> ranges, String value) {
        return ranges.stream().filter(range -> value.equals(range.value())).toList();
    }

    private static int sum(List<PageRangeRow> ranges) {
        return ranges.stream().mapToInt(PageRangeRow::pages).sum();
    }

    /** The pages of {@code ranges} that {@code covering} also holds; both by first page, neither with a page twice. */
    private static int covered(List<PageRangeRow> ranges, List<PageRangeRow> covering) {
        int pages = 0;
        int start = 0;
        for (PageRangeRow range : ranges) {
            while (start < covering.size() && covering.get(start).lastPage() < range.firstPage()) {
                start++;
            }
            for (int index = start; index < covering.size() && covering.get(index).firstPage() <= range.lastPage();
                 index++) {
                PageRangeRow other = covering.get(index);
                pages += Math.min(range.lastPage(), other.lastPage()) - Math.max(range.firstPage(), other.firstPage())
                        + 1;
            }
        }
        return pages;
    }

    /** One read-only transaction for the reads of one answer. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}
