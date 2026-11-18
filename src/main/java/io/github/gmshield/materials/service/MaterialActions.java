package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.NewestVersion;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
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
 * The GM's actions on a material's newest version (Story 1.20), for the owner of the work ({@link OwnerContext}). Each
 * locks that version and acts only from a state that allows it ({@link Materials#actions}, the one place of that rule),
 * in one transaction; anything else is {@code material.action_unavailable} (409) and changes nothing:
 *
 * <ul>
 *   <li>{@value Materials#ACCEPT}: {@code needs_review} becomes {@code ready}, and a {@code ready} version of the same
 *       material before it becomes {@code superseded};</li>
 *   <li>{@value Materials#REPROCESS}: the unreadable pages of a {@code needs_review} version are read again
 *       ({@link IngestionSteps#reprocess});</li>
 *   <li>{@value Materials#RETRY}: a {@code failed} version starts again ({@link IngestionSteps#retry});</li>
 *   <li>{@value Materials#DISCARD}: a {@code needs_review} or {@code failed} version becomes {@code discarded} with its
 *       removal pending, its unended steps cancelled; after the commit {@link VersionRemoval} runs once, and the
 *       {@link VersionRemovalSweep} finishes it if it stops.</li>
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
    private final Materials reads;
    private final IngestionSettings settings;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<Clock> clock;

    MaterialActions(MaterialStore materials, UploadStorage uploads, IngestionSteps ingestion, VersionRemoval removal,
                    Materials reads, IngestionSettings settings, ObjectProvider<PlatformTransactionManager> transactions,
                    ObjectProvider<Clock> clock) {
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
            Optional<NewestVersion> newest = materials.newest(materialId);
            if (newest.isEmpty()) {
                return Optional.<VersionRow>empty();
            }
            VersionRow version = materials.lockVersion(newest.get().version().id())
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
                case Materials.REPROCESS -> ingestion.reprocess(version);
                case Materials.RETRY -> ingestion.retry(version);
                default -> discard(version);
            }
            return Optional.of(version);
        });
        if (acted == null || acted.isEmpty()) {
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
    }

    private void discard(VersionRow version) {
        int cancelled = ingestion.cancelSteps(version.id());
        materials.discardVersion(version.id(), now());
        log.atInfo().addKeyValue("versionId", version.id()).addKeyValue("cancelled", cancelled)
                .log("material.discarded");
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
