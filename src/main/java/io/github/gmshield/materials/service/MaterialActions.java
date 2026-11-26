package io.github.gmshield.materials.service;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.CurrentVersion;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The GM's actions on a material's current version (Story 1.20; from Story 3.4 the newest one that is not
 * {@code discarded}: {@link MaterialStore#current}), for the owner of the work ({@link OwnerContext}). Each
 * locks that version and acts only from a state that allows it ({@link Materials#actions}, the one place of that rule),
 * in one transaction; anything else is {@code material.action_unavailable} (409) and changes nothing:
 *
 * <ul>
 *   <li>{@value Materials#ACCEPT}: {@code needs_review} becomes {@code ready}, and a {@code ready} version of the same
 *       material before it becomes {@code superseded} (it keeps its points); from Story 2.1 the version's passages get
 *       their index changes ({@link IndexChanges#start}) in the same transaction; from Story 4.5, before those start,
 *       a version that reprocessing made from an accepted one takes over that version's corrections
 *       ({@link ExtractedRecords#carryCorrections});</li>
 *   <li>{@value Materials#REPROCESS}: the unreadable pages of a {@code needs_review} version are read again
 *       ({@link IngestionSteps#reprocess}); from Story 3.4, a {@code ready} version is read again as a new version of
 *       its material, from a copy of its cleaned original ({@link IngestionSteps#reprocessAccepted}), after the check
 *       of {@link NewVersions#lockReady} with the material locked; the ready version is not changed;</li>
 *   <li>{@value Materials#RETRY}: a {@code failed} version starts again ({@link IngestionSteps#retry});</li>
 *   <li>{@value Materials#DISCARD}: a {@code needs_review} or {@code failed} version becomes {@code discarded} with its
 *       removal pending, its unended steps cancelled; from Story 2.1 its unended index changes are cancelled and the
 *       removal of its points is created ({@link IndexRemovals#start}); after the commit {@link VersionRemoval} runs
 *       once, and the {@link VersionRemovalSweep} finishes it if it stops.</li>
 * </ul>
 *
 * Each answers the material as {@link Materials#find} reads it after the action; empty for an unknown material or
 * another owner's. Nothing here logs a name.
 */
@Service
public class MaterialActions {

    private static final Logger log = LoggerFactory.getLogger(MaterialActions.class);

    private final MaterialStore materials;
    private final UploadStorage uploads;
    private final IngestionSteps ingestion;
    private final VersionRemoval removal;
    private final IndexChanges indexChanges;
    private final IndexRemovals indexRemovals;
    private final Materials reads;
    private final IngestionSettings settings;
    private final Library library;
    private final ExtractedRecords records;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<Clock> clock;

    MaterialActions(MaterialStore materials, UploadStorage uploads, IngestionSteps ingestion, VersionRemoval removal,
                    IndexChanges indexChanges, IndexRemovals indexRemovals, Materials reads, IngestionSettings settings,
                    Library library, ExtractedRecords records, Vault vault,
                    ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.library = library;
        this.records = records;
        this.vault = vault;
        this.indexChanges = indexChanges;
        this.indexRemovals = indexRemovals;
        this.settings = settings;
        this.materials = materials;
        this.uploads = uploads;
        this.ingestion = ingestion;
        this.removal = removal;
        this.reads = reads;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Runs {@code action} ({@link Materials#ACTIONS}) on the owner's material; see the class. */
    public Optional<Materials.Material> act(UUID materialId, String action) {
        if (!Materials.ACTIONS.contains(action)) {
            throw new IllegalArgumentException("material.action_unknown");
        }
        OwnerContext.require();
        Optional<VersionRow> acted = new TransactionTemplate(transactions.getObject()).execute(status -> {
            Optional<CurrentVersion> current = materials.current(materialId);
            if (current.isEmpty()) {
                return Optional.<VersionRow>empty();
            }
            VersionRow version = materials.lockVersion(current.get().version().id())
                    .orElseThrow(MaterialProblems::actionUnavailable);
            IngestionSteps.RetryCheck retry = IngestionSteps.retryCheck(materials, uploads, settings, version);
            if (Materials.RETRY.equals(action) && retry == IngestionSteps.RetryCheck.PAST_CAP) {
                throw MaterialProblems.tooManySteps();
            }
            if (!allowed(version, retry).contains(action)) {
                throw MaterialProblems.actionUnavailable();
            }
            switch (action) {
                case Materials.ACCEPT -> accept(version);
                case Materials.REPROCESS -> reprocess(version);
                case Materials.RETRY -> ingestion.retry(version);
                default -> discard(version);
            }
            return Optional.of(version);
        });
        if (acted.isEmpty()) {
            return Optional.empty();
        }
        log.atInfo().addKeyValue("materialId", materialId).addKeyValue("versionId", acted.get().id())
                .addKeyValue("action", action).log("material.action");
        if (Materials.DISCARD.equals(action)) {
            removeOnce(acted.get().id());
        }
        return reads.find(materialId);
    }

    /** The actions the locked version allows now, by the rule the material's view states. */
    private List<String> allowed(VersionRow version, IngestionSteps.RetryCheck retry) {
        int unreadable = IngestionSteps.NEEDS_REVIEW.equals(version.state())
                ? materials.pageOutcomes(List.of(version.id())).stream()
                        .filter(range -> PageRangeRow.UNREADABLE.equals(range.value()))
                        .mapToInt(PageRangeRow::pages).sum()
                : 0;
        return Materials.actions(version.state(), unreadable, retry == IngestionSteps.RetryCheck.ALLOWED);
    }

    private void accept(VersionRow version) {
        Instant now = now();
        for (VersionRow other : materials.versionsOf(version.materialId())) {
            if (!other.id().equals(version.id()) && Materials.READY.equals(other.state())) {
                materials.changeVersionState(other.id(), Materials.SUPERSEDED, now);
            }
        }
        materials.changeVersionState(version.id(), Materials.READY, now);
        // Story 4.5: before its index changes start, so the records they index are the corrected ones.
        materials.reprocessedFrom(version.id()).ifPresent(from -> carryCorrections(from, version));
        indexChanges.start(version);
    }

    /**
     * The corrections of the version {@code from} carried over to {@code version}, which reprocessing made from it
     * (Story 4.5; never for a replacement upload, which names no version). Logs the counts only.
     */
    private void carryCorrections(UUID from, VersionRow version) {
        // A correction locks its version first (RecordIndexing): with this lock, none of the replaced version's runs
        // meanwhile, and one that waited finds the version superseded once this commits.
        materials.lockVersion(from);
        ExtractedRecords.Carried carried = records.carryCorrections(from, version.id(),
                new PassageRemap(materials, vault, OwnerContext.require(), version.id()));
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("fromVersionId", from)
                .addKeyValue("matched", carried.matched()).addKeyValue("copied", carried.copied())
                .addKeyValue("dropped", carried.dropped()).log("material.corrections_carried");
    }

    /** In place while the version needs review; as a new version while it is ready (Story 3.4). */
    private void reprocess(VersionRow version) {
        if (Materials.READY.equals(version.state())) {
            NewVersions.Ready ready = NewVersions.lockReady(materials, library, version.materialId());
            ingestion.reprocessAccepted(ready.version());
        } else {
            ingestion.reprocess(version);
        }
    }

    private void discard(VersionRow version) {
        int cancelled = ingestion.cancelSteps(version.id());
        int indexCancelled = indexChanges.cancel(version.id());
        materials.discardVersion(version.id(), now());
        indexRemovals.start(version.id());
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("cancelled", cancelled)
                .addKeyValue("indexCancelled", indexCancelled).log("material.discarded");
    }

    /** The removal, once, after the commit: a failure is the sweep's to finish, never the GM's. */
    private void removeOnce(UUID versionId) {
        try {
            removal.remove(versionId);
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("versionId", versionId).addKeyValue("errorType", failure.getClass().getName())
                    .log("material.removal_deferred");
        }
    }

    private Instant now() {
        return clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS);
    }
}
