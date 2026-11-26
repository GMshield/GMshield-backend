package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A corrected record indexed again (Story 4.5), for the {@code knowledge} module, which writes the correction: the
 * {@code records} run of its first page joins its version's set of index changes ({@link IndexChanges#startPage}), so
 * its point keeps its id (the record's) and gets the new text and record type. Both calls act for the owner of the work
 * ({@link OwnerContext}) in the caller's transaction, which must exist, and lock the version until it ends: a correction
 * and an acceptance of a version that replaces it (which carries corrections over, {@link MaterialActions}) run one
 * after the other. Another owner's version reads as an unknown one. From Story 4.5 part B a deleted record's point goes
 * by a job of its own ({@link #removePoint}, {@link RecordRemovals}).
 */
@Service
@NamedInterface("service")
public class RecordIndexing {

    private final MaterialStore materials;
    private final IndexChanges changes;
    private final RecordRemovals removals;

    RecordIndexing(MaterialStore materials, IndexChanges changes, RecordRemovals removals) {
        this.materials = materials;
        this.changes = changes;
        this.removals = removals;
    }

    /** Locks the owner's version {@code versionId}; true when it is {@code ready} (only its records are corrected). */
    public boolean lockReady(UUID versionId) {
        requireTransaction();
        return ready(versionId).isPresent();
    }

    /**
     * Adds a records run of {@code page} to the owner's {@code ready} version {@code versionId}, as the class says;
     * false, and nothing added, when the version is not the owner's or not {@code ready}.
     */
    public boolean reindexPage(UUID versionId, int page) {
        requireTransaction();
        Optional<VersionRow> version = ready(versionId);
        version.ifPresent(found -> changes.startPage(found, page));
        return version.isPresent();
    }

    /**
     * Creates the removal of the point of the owner's record {@code recordId}, which the GM deleted, from its
     * {@code ready} version {@code versionId}'s points (Story 4.5 part B), as {@link RecordRemovals} says; false, and
     * nothing created, when the version is not the owner's or not {@code ready}.
     */
    public boolean removePoint(UUID versionId, UUID recordId) {
        requireTransaction();
        Optional<VersionRow> version = ready(versionId);
        version.ifPresent(found -> removals.start(found.id(), recordId));
        return version.isPresent();
    }

    private Optional<VersionRow> ready(UUID versionId) {
        return materials.lockVersion(versionId).filter(version -> Materials.READY.equals(version.state()));
    }

    private static void requireTransaction() {
        OwnerContext.require();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("index.transaction_required");
        }
    }
}
