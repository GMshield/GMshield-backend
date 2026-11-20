package io.github.gmshield.materials.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code material} and {@code material_version} in memory, for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one; like
 * the tables, it keeps the version's (owner, material) reference. A write undoes itself when its transaction rolls
 * back.
 */
public class InMemoryMaterialStore implements MaterialStore {

    public final Map<UUID, MaterialRow> materials = new ConcurrentHashMap<>();
    public final Map<UUID, VersionRow> versions = new ConcurrentHashMap<>();
    public final Map<UUID, MaterialSystemRow> systems = new ConcurrentHashMap<>();
    /** {@code ingestion_step} (Story 1.15). */
    public final Map<UUID, StepRow> steps = new ConcurrentHashMap<>();
    /** {@code version_file} (Story 1.15). */
    public final Map<UUID, VersionFileRow> files = new ConcurrentHashMap<>();
    /** {@code page_layer} (Story 1.16). */
    public final Map<UUID, PageRangeRow> layers = new ConcurrentHashMap<>();
    /** {@code page_outcome} (Story 1.16). */
    public final Map<UUID, PageRangeRow> outcomes = new ConcurrentHashMap<>();
    /** {@code passage} (Story 1.18). */
    public final Map<UUID, PassageRow> passages = new ConcurrentHashMap<>();
    /** {@code version_outline} (Story 1.18). */
    public final Map<UUID, OutlineRow> outlines = new ConcurrentHashMap<>();
    /** {@code page_view} (Story 1.19). */
    public final Map<UUID, PageViewRow> pageViews = new ConcurrentHashMap<>();

    @Override
    public synchronized void insertMaterial(MaterialRow row) {
        ownerFor(row.ownerId());
        if (materials.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("unique key taken");
        }
        RollbackUndo.onRollback(() -> materials.remove(row.id()));
    }

    @Override
    public synchronized void insertVersion(VersionRow row) {
        UUID owner = ownerFor(row.ownerId());
        MaterialRow material = materials.get(row.materialId());
        if (versions.containsKey(row.id()) || material == null || !material.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        versions.put(row.id(), row);
        RollbackUndo.onRollback(() -> versions.remove(row.id()));
    }

    @Override
    public synchronized void insertMaterialSystem(MaterialSystemRow row) {
        UUID owner = ownerFor(row.ownerId());
        MaterialRow material = materials.get(row.materialId());
        boolean taken = systems.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && other.materialId().equals(row.materialId()) && other.systemId().equals(row.systemId()));
        if (systems.containsKey(row.id()) || taken || material == null || !material.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        systems.put(row.id(), row);
        RollbackUndo.onRollback(() -> systems.remove(row.id()));
    }

    @Override
    public List<MaterialSystemRow> systemsOf(UUID materialId) {
        UUID owner = OwnerContext.require();
        return systems.values().stream().filter(row -> row.ownerId().equals(owner) && row.materialId().equals(materialId))
                .sorted(Comparator.comparing(MaterialSystemRow::createdAt).thenComparing(MaterialSystemRow::id)).toList();
    }

    @Override
    public List<NewestVersion> newestIn(Collection<String> states, UUID before, int limit) {
        UUID owner = OwnerContext.require();
        return materials.values().stream()
                .filter(row -> row.ownerId().equals(owner) && (before == null || row.id().compareTo(before) < 0))
                .sorted(Comparator.comparing(MaterialRow::id).reversed())
                .map(row -> newestOf(row).orElse(null))
                .filter(found -> found != null && states.contains(found.version().state()))
                .limit(Math.max(limit, 0)).toList();
    }

    @Override
    public List<NewestVersion> newestAttached(Collection<String> states, UUID systemId, Collection<UUID> campaignIds,
                                              UUID before, int limit) {
        UUID owner = OwnerContext.require();
        return newestIn(states, before, Integer.MAX_VALUE).stream()
                .filter(found -> (found.material().campaignId() != null && campaignIds.contains(found.material().campaignId()))
                        || systems.values().stream().anyMatch(row -> row.ownerId().equals(owner)
                                && row.materialId().equals(found.material().id()) && row.systemId().equals(systemId)))
                .limit(Math.max(limit, 0)).toList();
    }

    @Override
    public Optional<NewestVersion> newest(UUID materialId) {
        return findMaterial(materialId).flatMap(this::newestOf);
    }

    private Optional<NewestVersion> newestOf(MaterialRow material) {
        return versions.values().stream().filter(version -> version.materialId().equals(material.id()))
                .max(Comparator.comparing(VersionRow::createdAt).thenComparing(VersionRow::id))
                .map(version -> new NewestVersion(material, version));
    }

    @Override
    public Optional<MaterialRow> findMaterial(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(materials.get(id)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public Optional<VersionRow> findVersion(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(versions.get(id)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public synchronized boolean changeVersion(UUID id, String state, String format, Long sizeBytes, Instant now) {
        Optional<VersionRow> found = findVersion(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            versions.put(id, new VersionRow(before.id(), before.ownerId(), before.materialId(), state, format, sizeBytes,
                    before.createdAt(), now, before.pageTotal(), before.pipelineVersion(), before.failureCode(),
                    before.removalPending(), before.indexRecipe()));
            RollbackUndo.onRollback(() -> versions.put(id, before));
        });
        return found.isPresent();
    }

    @Override
    public synchronized boolean changeVersionState(UUID id, String state, Instant now) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(), state,
                before.format(), before.sizeBytes(), before.createdAt(), now, before.pageTotal(), before.pipelineVersion(),
                before.failureCode(), before.removalPending(), before.indexRecipe()));
    }

    @Override
    public synchronized boolean recordIngestion(UUID id, String pipelineVersion, Integer pageTotal, Instant now) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(),
                before.state(), before.format(), before.sizeBytes(), before.createdAt(), now,
                pageTotal == null ? before.pageTotal() : pageTotal,
                pipelineVersion == null ? before.pipelineVersion() : pipelineVersion, before.failureCode(),
                before.removalPending(), before.indexRecipe()));
    }

    @Override
    public Optional<VersionRow> lockVersion(UUID id) {
        return findVersion(id);
    }

    @Override
    public synchronized boolean clearPipelineVersion(UUID id, Instant now) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(),
                before.state(), before.format(), before.sizeBytes(), before.createdAt(), now, before.pageTotal(), null,
                before.failureCode(), before.removalPending(), before.indexRecipe()));
    }

    @Override
    public synchronized boolean failVersion(UUID id, String code, Instant now) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(), "failed",
                before.format(), before.sizeBytes(), before.createdAt(), now, before.pageTotal(), before.pipelineVersion(),
                code, before.removalPending(), before.indexRecipe()));
    }

    private boolean replaceVersion(UUID id, java.util.function.UnaryOperator<VersionRow> change) {
        Optional<VersionRow> found = findVersion(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            versions.put(id, change.apply(before));
            RollbackUndo.onRollback(() -> versions.put(id, before));
        });
        return found.isPresent();
    }

    @Override
    public synchronized void insertStep(StepRow row) {
        UUID owner = ownerFor(row.ownerId());
        VersionRow version = versions.get(row.versionId());
        boolean jobTaken = row.jobId() != null && steps.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && row.jobId().equals(other.jobId()));
        // The table's checks: an input file or every text file, never both; only a step over every text file waits
        // for its job.
        boolean checked = (row.inputTexts() == (row.inputFileId() == null)) && (row.jobId() != null || row.inputTexts());
        if (steps.containsKey(row.id()) || jobTaken || !checked || version == null || !version.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        steps.put(row.id(), row);
        RollbackUndo.onRollback(() -> steps.remove(row.id()));
    }

    @Override
    public Optional<StepRow> findStepByJob(UUID jobId) {
        UUID owner = OwnerContext.require();
        return steps.values().stream().filter(row -> row.ownerId().equals(owner) && jobId.equals(row.jobId())).findFirst();
    }

    @Override
    public long countSteps(UUID versionId) {
        UUID owner = OwnerContext.require();
        return steps.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId))
                .count();
    }

    @Override
    public synchronized boolean endStep(UUID jobId, Instant now) {
        Optional<StepRow> found = findStepByJob(jobId);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            if (before.endedAt() == null) {
                steps.put(before.id(), before.ended(now));
                RollbackUndo.onRollback(() -> steps.put(before.id(), before));
            }
        });
        return found.isPresent();
    }

    @Override
    public synchronized boolean endHeldStep(UUID stepId, Instant now) {
        return changeHeld(stepId, before -> before.ended(now));
    }

    @Override
    public synchronized boolean startHeldStep(UUID stepId, UUID jobId) {
        boolean taken = steps.values().stream().anyMatch(other -> jobId.equals(other.jobId()));
        if (taken) {
            throw new IllegalStateException("unique key taken");
        }
        return changeHeld(stepId, before -> before.started(jobId));
    }

    private boolean changeHeld(UUID stepId, java.util.function.UnaryOperator<StepRow> change) {
        UUID owner = OwnerContext.require();
        Optional<StepRow> found = Optional.ofNullable(steps.get(stepId))
                .filter(row -> row.ownerId().equals(owner) && row.held());
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            steps.put(stepId, change.apply(before));
            RollbackUndo.onRollback(() -> steps.put(stepId, before));
        });
        return found.isPresent();
    }

    @Override
    public List<StepRow> unendedSteps(UUID versionId) {
        UUID owner = OwnerContext.require();
        return steps.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId)
                        && row.endedAt() == null)
                .sorted(Comparator.comparing(StepRow::createdAt).thenComparing(StepRow::id)).toList();
    }

    @Override
    public List<StepRow> unendedSteps(Collection<UUID> versionIds) {
        rangeReads.incrementAndGet();
        UUID owner = OwnerContext.require();
        return steps.values().stream().filter(row -> row.ownerId().equals(owner)
                        && versionIds.contains(row.versionId()) && row.endedAt() == null)
                .sorted(Comparator.comparing(StepRow::createdAt).thenComparing(StepRow::id)).toList();
    }

    @Override
    public synchronized void insertVersionFile(VersionFileRow row) {
        UUID owner = ownerFor(row.ownerId());
        VersionRow version = versions.get(row.versionId());
        boolean fileTaken = files.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && other.fileId().equals(row.fileId()));
        if (files.containsKey(row.id()) || fileTaken || version == null || !version.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        files.put(row.id(), row);
        RollbackUndo.onRollback(() -> files.remove(row.id()));
    }

    @Override
    public List<VersionFileRow> versionFiles(UUID versionId) {
        UUID owner = OwnerContext.require();
        return files.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId))
                .sorted(Comparator.comparing(VersionFileRow::role).reversed().thenComparing(VersionFileRow::firstPage)
                        .thenComparing(VersionFileRow::id)).toList();
    }

    @Override
    public List<VersionFileRow> versionFiles(UUID versionId, Collection<String> roles) {
        return versionFiles(versionId).stream().filter(row -> roles.contains(row.role())).toList();
    }

    @Override
    public Optional<VersionFileRow> versionFile(UUID versionId, UUID fileId) {
        return versionFiles(versionId).stream().filter(row -> row.fileId().equals(fileId)).findFirst();
    }

    @Override
    public synchronized Optional<VersionFileRow> deleteVersionFile(UUID versionId, UUID fileId) {
        Optional<VersionFileRow> found = versionFile(versionId, fileId);
        found.ifPresent(row -> {
            ownerFor(row.ownerId());
            refusePictureInUse(row);
            files.remove(row.id());
            RollbackUndo.onRollback(() -> files.put(row.id(), row));
        });
        return found;
    }

    /** Like the key of {@code page_view} on its picture: a file a page view shows cannot go first. */
    private void refusePictureInUse(VersionFileRow row) {
        if (pageViews.values().stream().anyMatch(view -> view.ownerId().equals(row.ownerId())
                && row.fileId().equals(view.pictureFileId()))) {
            throw new IllegalStateException("reference refused");
        }
    }

    @Override
    public synchronized List<VersionFileRow> deleteVersionFiles(UUID versionId) {
        List<VersionFileRow> found = versionFiles(versionId);
        found.forEach(this::refusePictureInUse);
        for (VersionFileRow row : found) {
            ownerFor(row.ownerId());
            files.remove(row.id());
            RollbackUndo.onRollback(() -> files.put(row.id(), row));
        }
        return found;
    }

    @Override
    public synchronized List<VersionFileRow> deleteVersionFiles(UUID versionId, Collection<String> roles) {
        List<VersionFileRow> found = versionFiles(versionId).stream().filter(row -> roles.contains(row.role())).toList();
        found.forEach(this::refusePictureInUse);
        for (VersionFileRow row : found) {
            ownerFor(row.ownerId());
            files.remove(row.id());
            RollbackUndo.onRollback(() -> files.put(row.id(), row));
        }
        return found;
    }

    @Override
    public synchronized void insertPageLayer(PageRangeRow row) {
        insertRange(layers, row);
    }

    @Override
    public synchronized void insertPageOutcome(PageRangeRow row) {
        insertRange(outcomes, row);
    }

    private void insertRange(Map<UUID, PageRangeRow> table, PageRangeRow row) {
        UUID owner = ownerFor(row.ownerId());
        VersionRow version = versions.get(row.versionId());
        if (table.containsKey(row.id()) || version == null || !version.ownerId().equals(owner)
                || row.firstPage() < 1 || row.lastPage() < row.firstPage()) {
            throw new IllegalStateException("key, reference or check refused");
        }
        table.put(row.id(), row);
        RollbackUndo.onRollback(() -> table.remove(row.id()));
    }

    @Override
    public boolean layerOverlaps(UUID versionId, int firstPage, int lastPage) {
        return !rangesOf(layers, versionId, firstPage, lastPage).isEmpty();
    }

    @Override
    public boolean outcomeOverlaps(UUID versionId, int firstPage, int lastPage) {
        return !rangesOf(outcomes, versionId, firstPage, lastPage).isEmpty();
    }

    @Override
    public List<PageRangeRow> outcomesWithin(UUID versionId, int firstPage, int lastPage) {
        return rangesOf(outcomes, versionId, firstPage, lastPage);
    }

    @Override
    public List<PageRangeRow> pageLayers(UUID versionId) {
        return rangesOf(layers, versionId, 1, Integer.MAX_VALUE);
    }

    private static List<PageRangeRow> rangesOf(Map<UUID, PageRangeRow> table, UUID versionId, int first, int last) {
        UUID owner = OwnerContext.require();
        return table.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId)
                        && row.firstPage() <= last && row.lastPage() >= first)
                .sorted(Comparator.comparing(PageRangeRow::firstPage)).toList();
    }

    /** How many times the batched range reads ran: a list of materials reads each kind once (Story 1.17). */
    public final AtomicInteger rangeReads = new AtomicInteger();

    @Override
    public List<PageRangeRow> pageLayers(Collection<UUID> versionIds) {
        rangeReads.incrementAndGet();
        return rangesOf(layers, versionIds);
    }

    @Override
    public List<PageRangeRow> pageOutcomes(Collection<UUID> versionIds) {
        rangeReads.incrementAndGet();
        return rangesOf(outcomes, versionIds);
    }

    private static List<PageRangeRow> rangesOf(Map<UUID, PageRangeRow> table, Collection<UUID> versionIds) {
        UUID owner = OwnerContext.require();
        return table.values().stream().filter(row -> row.ownerId().equals(owner) && versionIds.contains(row.versionId()))
                .sorted(Comparator.comparing(PageRangeRow::versionId).thenComparing(PageRangeRow::firstPage)).toList();
    }

    @Override
    public Map<UUID, Long> pagesDone(Collection<UUID> versionIds) {
        Map<UUID, Long> done = new HashMap<>();
        for (UUID versionId : versionIds) {
            long pages = rangesOf(outcomes, versionId, 1, Integer.MAX_VALUE).stream().mapToLong(PageRangeRow::pages).sum();
            if (pages > 0) {
                done.put(versionId, pages);
            }
        }
        return done;
    }

    @Override
    public synchronized int deletePageRanges(UUID versionId) {
        int removed = 0;
        for (Map<UUID, PageRangeRow> table : List.of(layers, outcomes)) {
            for (PageRangeRow row : rangesOf(table, versionId, 1, Integer.MAX_VALUE)) {
                ownerFor(row.ownerId());
                table.remove(row.id());
                RollbackUndo.onRollback(() -> table.put(row.id(), row));
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized void insertPassages(List<PassageRow> rows) {
        for (PassageRow row : rows) {
            UUID owner = ownerFor(row.ownerId());
            VersionRow version = versions.get(row.versionId());
            boolean ordinalTaken = passages.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                    && other.versionId().equals(row.versionId()) && other.ordinal() == row.ordinal());
            if (passages.containsKey(row.id()) || ordinalTaken || version == null || !version.ownerId().equals(owner)
                    || row.ordinal() < 0 || row.page() < 1) {
                throw new IllegalStateException("key, reference or check refused");
            }
            passages.put(row.id(), row);
            RollbackUndo.onRollback(() -> passages.remove(row.id()));
        }
    }

    @Override
    public Optional<PassageRow> findPassage(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(passages.get(id)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public List<PassageRow> passages(UUID versionId) {
        UUID owner = OwnerContext.require();
        return passages.values().stream().filter(row -> row.ownerId().equals(owner)
                && row.versionId().equals(versionId)).sorted(Comparator.comparingInt(PassageRow::ordinal)).toList();
    }

    @Override
    public List<Integer> passageOrdinals(UUID versionId) {
        return passages(versionId).stream().map(PassageRow::ordinal).toList();
    }

    @Override
    public long countPassages(UUID versionId, int first, int last) {
        return passages(versionId, first, last).size();
    }

    @Override
    public List<PassageRow> passages(UUID versionId, int first, int last) {
        return passages(versionId).stream().filter(row -> row.ordinal() >= first && row.ordinal() <= last).toList();
    }

    @Override
    public synchronized int deletePassages(UUID versionId) {
        List<PassageRow> found = passages(versionId);
        for (PassageRow row : found) {
            ownerFor(row.ownerId());
            passages.remove(row.id());
            RollbackUndo.onRollback(() -> passages.put(row.id(), row));
        }
        return found.size();
    }

    @Override
    public synchronized void insertOutline(OutlineRow row) {
        UUID owner = ownerFor(row.ownerId());
        VersionRow version = versions.get(row.versionId());
        boolean taken = outlines.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && other.versionId().equals(row.versionId()));
        if (outlines.containsKey(row.id()) || taken || version == null || !version.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        outlines.put(row.id(), row);
        RollbackUndo.onRollback(() -> outlines.remove(row.id()));
    }

    @Override
    public Optional<OutlineRow> findOutline(UUID versionId) {
        UUID owner = OwnerContext.require();
        return outlines.values().stream().filter(row -> row.ownerId().equals(owner)
                && row.versionId().equals(versionId)).findFirst();
    }

    @Override
    public synchronized boolean deleteOutline(UUID versionId) {
        Optional<OutlineRow> found = findOutline(versionId);
        found.ifPresent(row -> {
            ownerFor(row.ownerId());
            outlines.remove(row.id());
            RollbackUndo.onRollback(() -> outlines.put(row.id(), row));
        });
        return found.isPresent();
    }

    @Override
    public synchronized void insertPageView(PageViewRow row) {
        UUID owner = ownerFor(row.ownerId());
        VersionRow version = versions.get(row.versionId());
        boolean taken = pageViews.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && other.versionId().equals(row.versionId()) && other.page() == row.page());
        boolean picture = row.pictureFileId() == null || files.values().stream().anyMatch(file
                -> file.ownerId().equals(owner) && file.fileId().equals(row.pictureFileId()));
        if (pageViews.containsKey(row.id()) || taken || version == null || !version.ownerId().equals(owner)
                || !picture || row.page() < 1 || (row.pictureFileId() == null) != (row.width() == null)
                || (row.width() == null) != (row.height() == null)) {
            throw new IllegalStateException("key, reference or check refused");
        }
        pageViews.put(row.id(), row);
        RollbackUndo.onRollback(() -> pageViews.remove(row.id()));
    }

    @Override
    public Optional<PageViewRow> findPageView(UUID versionId, int page) {
        UUID owner = OwnerContext.require();
        return pageViews.values().stream().filter(row -> row.ownerId().equals(owner)
                && row.versionId().equals(versionId) && row.page() == page).findFirst();
    }

    @Override
    public synchronized List<PageViewRow> deletePageViews(UUID versionId, int firstPage, int lastPage) {
        UUID owner = OwnerContext.require();
        List<PageViewRow> found = pageViews.values().stream().filter(row -> row.ownerId().equals(owner)
                        && row.versionId().equals(versionId) && row.page() >= firstPage && row.page() <= lastPage)
                .sorted(Comparator.comparingInt(PageViewRow::page)).toList();
        for (PageViewRow row : found) {
            pageViews.remove(row.id());
            RollbackUndo.onRollback(() -> pageViews.put(row.id(), row));
        }
        return found;
    }

    @Override
    public int deletePageViews(UUID versionId) {
        return deletePageViews(versionId, 1, Integer.MAX_VALUE).size();
    }

    @Override
    public List<PassageRow> passagesOnPage(UUID versionId, int page) {
        return passages(versionId).stream().filter(row -> row.page() == page).toList();
    }

    @Override
    public List<VersionRow> versionsOf(UUID materialId) {
        UUID owner = OwnerContext.require();
        return versions.values().stream().filter(row -> row.ownerId().equals(owner)
                        && row.materialId().equals(materialId))
                .sorted(Comparator.comparing(VersionRow::createdAt).thenComparing(VersionRow::id)).toList();
    }

    @Override
    public List<StepRow> steps(UUID versionId) {
        UUID owner = OwnerContext.require();
        return steps.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId))
                .sorted(Comparator.comparing(StepRow::createdAt).thenComparing(StepRow::id)).toList();
    }

    @Override
    public synchronized int deleteOutcomes(UUID versionId, String outcome) {
        List<PageRangeRow> found = rangesOf(outcomes, versionId, 1, Integer.MAX_VALUE).stream()
                .filter(row -> row.value().equals(outcome)).toList();
        for (PageRangeRow row : found) {
            ownerFor(row.ownerId());
            outcomes.remove(row.id());
            RollbackUndo.onRollback(() -> outcomes.put(row.id(), row));
        }
        return found.size();
    }

    @Override
    public List<Integer> pagesWithView(UUID versionId, int firstPage, int lastPage) {
        UUID owner = OwnerContext.require();
        return pageViews.values().stream().filter(row -> row.ownerId().equals(owner)
                        && row.versionId().equals(versionId) && row.page() >= firstPage && row.page() <= lastPage)
                .map(PageViewRow::page).sorted().toList();
    }

    @Override
    public synchronized boolean discardVersion(UUID id, Instant now) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(),
                "discarded", before.format(), before.sizeBytes(), before.createdAt(), now, before.pageTotal(),
                before.pipelineVersion(), before.failureCode(), true, before.indexRecipe()));
    }

    @Override
    public synchronized boolean clearRemovalPending(UUID id, Instant now) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(),
                before.state(), before.format(), before.sizeBytes(), before.createdAt(), now, before.pageTotal(),
                before.pipelineVersion(), before.failureCode(), false, before.indexRecipe()));
    }

    @Override
    public synchronized boolean recordIndexRecipe(UUID id, String recipe) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(),
                before.state(), before.format(), before.sizeBytes(), before.createdAt(), before.updatedAt(),
                before.pageTotal(), before.pipelineVersion(), before.failureCode(), before.removalPending(), recipe));
    }

    @Override
    public List<VersionRow> readyOutside(String recipe) {
        UUID owner = OwnerContext.require();
        return versions.values().stream().filter(row -> row.ownerId().equals(owner) && row.state().equals("ready")
                        && !recipe.equals(row.indexRecipe()))
                .sorted(Comparator.comparing(VersionRow::createdAt).thenComparing(VersionRow::id)).toList();
    }

    @Override
    public List<VersionRow> readyOfCampaign(UUID campaignId) {
        UUID owner = OwnerContext.require();
        return readyOf(material -> material.ownerId().equals(owner) && campaignId != null
                && campaignId.equals(material.campaignId()));
    }

    @Override
    public List<VersionRow> readyOfSystem(UUID systemId) {
        UUID owner = OwnerContext.require();
        java.util.Set<UUID> attached = systems.values().stream()
                .filter(row -> row.ownerId().equals(owner) && row.systemId().equals(systemId))
                .map(MaterialSystemRow::materialId).collect(java.util.stream.Collectors.toSet());
        return readyOf(material -> material.ownerId().equals(owner) && attached.contains(material.id()));
    }

    private List<VersionRow> readyOf(java.util.function.Predicate<MaterialRow> material) {
        UUID owner = OwnerContext.require();
        return versions.values().stream().filter(row -> row.ownerId().equals(owner) && row.state().equals("ready"))
                .filter(row -> {
                    MaterialRow found = materials.get(row.materialId());
                    return found != null && material.test(found);
                })
                .sorted(Comparator.comparing(VersionRow::createdAt).thenComparing(VersionRow::id)).toList();
    }

    @Override
    public List<UUID> passageIds(UUID versionId, int first, int last) {
        return passages(versionId, first, last).stream().map(PassageRow::id).toList();
    }

    @Override
    public List<PassageRow> findPassages(Collection<UUID> ids) {
        UUID owner = OwnerContext.require();
        return ids.stream().distinct().map(passages::get)
                .filter(row -> row != null && row.ownerId().equals(owner)).toList();
    }

    /** The owners of every {@code ready} version whose index recipe is not {@code recipe} (Story 2.1: the reconcile). */
    public List<UUID> staleOwners(String recipe) {
        return versions.values().stream().filter(row -> row.state().equals("ready")
                && !recipe.equals(row.indexRecipe())).map(VersionRow::ownerId).distinct().sorted().toList();
    }

    /** The versions of every owner whose removal is pending, by last change (Story 1.20: the removal sweep's list). */
    public List<VersionRow> removalPending(int limit) {
        return versions.values().stream().filter(VersionRow::removalPending)
                .sorted(Comparator.comparing(VersionRow::updatedAt).thenComparing(VersionRow::id)).limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean deleteVersion(UUID id) {
        Optional<VersionRow> found = findVersion(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            versions.remove(id);
            RollbackUndo.onRollback(() -> versions.put(id, before));
        });
        return found.isPresent();
    }

    @Override
    public synchronized boolean deleteMaterial(UUID id) {
        Optional<MaterialRow> found = findMaterial(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            if (versions.values().stream().anyMatch(version -> version.materialId().equals(id))) {
                throw new IllegalStateException("a version still names the material");
            }
            materials.remove(id);
            RollbackUndo.onRollback(() -> materials.put(id, before));
            for (MaterialSystemRow link : List.copyOf(systems.values())) {
                if (link.materialId().equals(id)) {
                    systems.remove(link.id());
                    RollbackUndo.onRollback(() -> systems.put(link.id(), link));
                }
            }
        });
        return found.isPresent();
    }

    /** The current owner, who must be the row's; a write outside a transaction is refused, as JPA refuses it. */
    private static UUID ownerFor(UUID rowOwner) {
        UUID owner = OwnerContext.require();
        if (!owner.equals(rowOwner)) {
            throw new IllegalStateException("another owner's row");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("no transaction");
        }
        return owner;
    }
}
