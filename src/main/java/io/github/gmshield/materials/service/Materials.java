package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.NewestVersion;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
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
 * and text ({@link #passage}), and a version's outline ({@link #outline}), decrypted, for their owner only.
 * Everything acts for the owner of the work ({@link OwnerContext}): another owner's material reads like an unknown one.
 * Nothing here logs a name.
 */
@Service
public class Materials {

    /** Every state a version may be in, as the tables write them. */
    public static final Set<String> STATES = Set.of("uploading", "waiting", "processing", "needs_review", "ready",
            "superseded", "failed", "discarded");
    static final String FAILED = "failed";
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private final MaterialStore materials;
    private final UploadStorage uploads;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final JobsInProgress inProgress;

    /** Those of these jobs of the bound owner that are offered or running ({@link Jobs#inProgress}). */
    @FunctionalInterface
    interface JobsInProgress {
        Set<UUID> of(Collection<UUID> jobIds);
    }

    @Autowired
    Materials(MaterialStore materials, UploadStorage uploads, Vault vault, Jobs jobs,
              ObjectProvider<PlatformTransactionManager> transactions) {
        this(materials, uploads, vault, jobs::inProgress, transactions);
    }

    Materials(MaterialStore materials, UploadStorage uploads, Vault vault, JobsInProgress inProgress,
              ObjectProvider<PlatformTransactionManager> transactions) {
        this.materials = materials;
        this.uploads = uploads;
        this.vault = vault;
        this.inProgress = inProgress;
        this.transactions = transactions;
    }

    /** An unfinished upload: its id, the bytes verified and stored, its length and when it expires. */
    public record Upload(UUID id, long offset, long length, Instant expiresAt) {
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
     * {@code pages} once the page total is known, and {@code failure} (a code) only when the state is {@code failed}.
     */
    public record Material(UUID id, String name, String state, String kind, Upload upload, Pages pages,
                           String failure) {
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

    /** The owner's passage with this id; empty for an unknown id or another owner's. */
    public Optional<Passage> passage(UUID id) {
        return reading(() -> materials.findPassage(id).flatMap(row -> materials.findVersion(row.versionId())
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

    private List<Material> views(List<NewestVersion> rows) {
        UUID owner = OwnerContext.require();
        List<UUID> uploading = rows.stream().map(NewestVersion::version)
                .filter(version -> Uploads.UPLOADING.equals(version.state())).map(version -> version.id()).toList();
        Map<UUID, UploadRow> open = uploads.joined().openForVersions(owner, uploading).stream()
                .collect(Collectors.toMap(UploadRow::versionId, Function.identity(), (first, second) -> first));
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
            return new Material(row.material().id(),
                    vault.decryptText(new Binding(Uploads.MATERIAL, Uploads.FIELD_NAME, row.material().id(),
                            row.material().ownerId()), row.material().name()),
                    row.version().state(), row.material().kind(),
                    upload == null ? null : new Upload(upload.id(), upload.offset(), upload.length(), upload.expiresAt()),
                    total == null ? null : pages(total, layers.getOrDefault(row.version().id(), List.of()),
                            outcomes.getOrDefault(row.version().id(), List.of()),
                            current.getOrDefault(row.version().id(), List.of())),
                    FAILED.equals(row.version().state()) ? row.version().failureCode() : null);
        }).toList();
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
