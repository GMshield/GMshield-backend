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
 * after the other. Another owner's version reads as an unknown one.
 */
@Service
@NamedInterface("service")
public class RecordIndexing {

    private final MaterialStore materials;
    private final IndexChanges changes;

    RecordIndexing(MaterialStore materials, IndexChanges changes) {
        this.materials = materials;
        this.changes = changes;
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
