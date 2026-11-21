package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.library.service.SystemDeletionHandler;
import io.github.gmshield.materials.data.IndexChangeRow;
import io.github.gmshield.materials.data.IndexStore;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.FileStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The materials' part of a system's deletion (Story 3.1 part B, AD-22), as the owner of the work. What goes: every
 * material whose campaign is one of the system's, and every material attached to the system and to no other. A
 * material attached to another system as well stays, and only loses its attachment to this one.
 *
 * <ul>
 *   <li>{@link #count}: the materials that go and still show, those whose newest version is not {@code discarded}.</li>
 *   <li>{@link #prepare}: each version of a material that goes is locked; the jobs of its unended steps and index
 *       changes are locked ({@link Jobs#claimLapse}) and cancelled ({@link IngestionSteps#cancelSteps},
 *       {@link IndexChanges#cancel}); a version not yet {@code discarded} becomes so, with its removal pending, so the
 *       material leaves every list and every scope at once. It answers the latest claim lapse of the jobs it
 *       cancelled. No index removal starts yet: an index change still running may write points until its claim
 *       lapses.</li>
 *   <li>{@link #remove}: for each version, with it locked, one {@code index.removal} job unless the version has one
 *       already ({@link IndexRemovals#start}: never two for one version, a version the GM discarded before included);
 *       then the discard's {@link VersionRemoval} (page views, passages, outline, page ranges and files); then the
 *       pieces of its uploads and files, and in one transaction every row left of it ({@code page_view},
 *       {@code passage}, {@code version_outline}, {@code page_layer}, {@code page_outcome}, {@code version_file},
 *       {@code upload}, {@code ingestion_step}, {@code index_change}) and the version itself; once a material has no
 *       version, the material with its attachments. Last, the attachment of each material that stays. A version
 *       that is no longer {@code discarded} (an upload completed during the wait) is left, and the pass fails, so the
 *       next one prepares it again and waits as needed.</li>
 * </ul>
 *
 * Every step is safe to repeat. Nothing here logs a name.
 *
 * <p>Story 3.2 part B: for a campaign's deletion ({@code Going(null, [campaign])}) what goes is the campaign's
 * materials alone; no material attached to a system goes, and no attachment is touched.
 */
@Service
class SystemMaterialsDeletion implements SystemDeletionHandler {

    private static final Logger log = LoggerFactory.getLogger(SystemMaterialsDeletion.class);

    private final MaterialStore materials;
    private final IndexStore index;
    private final UploadStorage uploads;
    private final IngestionSteps ingestion;
    private final IndexChanges indexChanges;
    private final IndexRemovals indexRemovals;
    private final VersionRemoval removal;
    private final FileStore files;
    private final Jobs jobs;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<Clock> clock;

    SystemMaterialsDeletion(MaterialStore materials, IndexStore index, UploadStorage uploads, IngestionSteps ingestion,
                            IndexChanges indexChanges, IndexRemovals indexRemovals, VersionRemoval removal,
                            FileStore files, Jobs jobs, ObjectProvider<PlatformTransactionManager> transactions,
                            ObjectProvider<Clock> clock) {
        this.materials = materials;
        this.index = index;
        this.uploads = uploads;
        this.ingestion = ingestion;
        this.indexChanges = indexChanges;
        this.indexRemovals = indexRemovals;
        this.removal = removal;
        this.files = files;
        this.jobs = jobs;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public String part() {
        return SystemDeletion.MATERIALS;
    }

    @Override
    public long count(Going going) {
        return going(going).stream()
                .filter(material -> materials.newest(material.id())
                        .filter(newest -> !Uploads.DISCARDED.equals(newest.version().state())).isPresent())
                .count();
    }

    @Override
    public Optional<Instant> prepare(Going going) {
        Instant now = now();
        Instant latest = null;
        int discarded = 0;
        int cancelled = 0;
        for (MaterialRow material : going(going)) {
            for (VersionRow listed : materials.versionsOf(material.id())) {
                Optional<VersionRow> locked = materials.lockVersion(listed.id());
                if (locked.isEmpty()) {
                    continue;
                }
                VersionRow version = locked.get();
                List<UUID> jobIds = new ArrayList<>();
                materials.unendedSteps(version.id()).stream().map(StepRow::jobId).filter(Objects::nonNull)
                        .forEach(jobIds::add);
                index.changes(version.id()).stream().map(IndexChangeRow::jobId).forEach(jobIds::add);
                Optional<Instant> lapse = jobs.claimLapse(jobIds);
                if (lapse.isPresent() && (latest == null || lapse.get().isAfter(latest))) {
                    latest = lapse.get();
                }
                cancelled += ingestion.cancelSteps(version.id()) + indexChanges.cancel(version.id());
                if (!Uploads.DISCARDED.equals(version.state())) {
                    materials.discardVersion(version.id(), now);
                    discarded++;
                }
            }
        }
        going.about(log.atInfo()).addKeyValue("discarded", discarded).addKeyValue("cancelled", cancelled)
                .log("material.system_deletion_prepared");
        return Optional.ofNullable(latest);
    }

    @Override
    public void remove(Going going) {
        UUID owner = OwnerContext.require();
        List<MaterialRow> gone = reading(() -> going(going));
        int left = 0;
        int removed = 0;
        for (MaterialRow material : gone) {
            for (VersionRow version : reading(() -> materials.versionsOf(material.id()))) {
                left += removeVersion(owner, version.id()) ? 0 : 1;
            }
            removed += Boolean.TRUE.equals(writing(() -> materials.versionsOf(material.id()).isEmpty()
                    && materials.deleteMaterial(material.id()))) ? 1 : 0;
        }
        List<UUID> goneIds = gone.stream().map(MaterialRow::id).toList();
        int detached = 0;
        UUID systemId = going.systemId();
        // A campaign's deletion has no system part: no attachment changes.
        List<MaterialRow> sharedOnes = systemId == null ? List.of()
                : reading(() -> materials.materialsAlsoOfOtherSystems(systemId));
        for (MaterialRow shared : sharedOnes) {
            if (!goneIds.contains(shared.id())) {
                detached += Boolean.TRUE.equals(writing(() -> materials.detachSystem(shared.id(), systemId))) ? 1 : 0;
            }
        }
        going.about(log.atInfo().addKeyValue("ownerId", owner)).addKeyValue("materials", removed)
                .addKeyValue("detached", detached).addKeyValue("left", left).log("material.system_deletion_removed");
        if (left > 0) {
            throw new IllegalStateException("material.version_not_discarded");
        }
    }

    /** Removes one discarded version, as the class says; false when it is no longer there or discarded (it is left). */
    private boolean removeVersion(UUID owner, UUID versionId) {
        // The removal of its points, once for the version: the version's lock orders two passes.
        boolean discarded = Boolean.TRUE.equals(writing(() -> {
            Optional<VersionRow> locked = materials.lockVersion(versionId);
            if (locked.isEmpty()) {
                return true;
            }
            if (!Uploads.DISCARDED.equals(locked.get().state())) {
                return false;
            }
            if (!index.hasRemoval(versionId)) {
                indexRemovals.start(versionId);
            }
            return true;
        }));
        if (!discarded) {
            return false;
        }
        removal.remove(versionId);
        // The pieces first, so a failure leaves rows that name them for the next pass.
        List<UUID> before = reading(() -> fileIds(owner, versionId));
        before.forEach(file -> files.deleteFile(owner, file));
        Optional<List<UUID>> rest = writing(() -> {
            Optional<VersionRow> locked = materials.lockVersion(versionId);
            if (locked.isEmpty() || !Uploads.DISCARDED.equals(locked.get().state())) {
                return Optional.<List<UUID>>empty();
            }
            List<UUID> named = new ArrayList<>();
            // Page views first: each names its picture file.
            materials.deletePageViews(versionId);
            materials.deletePassages(versionId);
            materials.deleteOutline(versionId);
            materials.deletePageRanges(versionId);
            materials.deleteVersionFiles(versionId).forEach(file -> named.add(file.fileId()));
            for (UploadRow upload : uploads.joined().allOfVersion(owner, versionId)) {
                uploads.joined().delete(upload.id());
                named.add(upload.id());
            }
            materials.deleteSteps(versionId);
            index.deleteChanges(versionId);
            materials.deleteVersion(versionId);
            return Optional.of(named);
        });
        // Again after the rows went: a piece an unfinished part wrote meanwhile goes too.
        rest.ifPresent(named -> named.forEach(file -> files.deleteFile(owner, file)));
        // A version no longer discarded is left, for the next pass.
        return rest.isPresent();
    }

    /** The files of a version and the pieces of its uploads, by their ids. */
    private List<UUID> fileIds(UUID owner, UUID versionId) {
        List<UUID> ids = new ArrayList<>();
        materials.versionFiles(versionId).stream().map(VersionFileRow::fileId).forEach(ids::add);
        uploads.joined().allOfVersion(owner, versionId).stream().map(UploadRow::id).forEach(ids::add);
        return ids;
    }

    /**
     * The materials that go: those of the system's campaigns, then those attached to it alone; each once. For a
     * campaign's deletion (no system), the campaign's alone.
     */
    private List<MaterialRow> going(Going going) {
        Map<UUID, MaterialRow> found = new LinkedHashMap<>();
        materials.materialsOfCampaigns(going.campaignIds()).forEach(material -> found.put(material.id(), material));
        if (going.systemId() != null) {
            materials.materialsOnlyOfSystem(going.systemId())
                    .forEach(material -> found.putIfAbsent(material.id(), material));
        }
        return List.copyOf(found.values());
    }


    private Instant now() {
        return clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS);
    }

    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    private <T> T writing(Supplier<T> write) {
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }
}
