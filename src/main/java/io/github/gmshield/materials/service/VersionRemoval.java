package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.FileStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The removal of a discarded material version (Story 1.20, AD-22), as its owner ({@link OwnerContext}). The discard
 * itself ({@link MaterialActions}) makes the version {@code discarded} with its removal pending, in the GM's
 * transaction; this then runs once after that commit, and the {@link VersionRemovalSweep} runs it again for any
 * version still flagged (a restart, a failure). It has three parts, each safe to repeat:
 *
 * <ol>
 *   <li>in one transaction, with the version locked: its page views, passages, outline, page layers and outcomes go;</li>
 *   <li>the file store removes the pieces of every version file (the original, batches, texts and pictures);</li>
 *   <li>in one transaction: the {@code version_file} rows go, the complete raw upload becomes {@code removing} (its
 *       pieces and row go after the commit, and the upload sweep finishes them), and the flag is cleared.</li>
 * </ol>
 *
 * The version row and its {@code ingestion_step} rows stay: they hold no content. Nothing here logs a name.
 */
@Service
public class VersionRemoval {

    private static final Logger log = LoggerFactory.getLogger(VersionRemoval.class);

    private final MaterialStore materials;
    private final FileStore files;
    private final IngestionSteps ingestion;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<Clock> clock;

    VersionRemoval(MaterialStore materials, FileStore files, IngestionSteps ingestion,
                   ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.materials = materials;
        this.files = files;
        this.ingestion = ingestion;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Removes what the owner's discarded version holds, as above; called outside any transaction. Answers whether it
     * finished now: false for an unknown version, another owner's, or one whose removal is not pending.
     */
    public boolean remove(UUID versionId) {
        UUID owner = OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        List<VersionFileRow> stored = template.execute(status -> {
            if (materials.lockVersion(versionId).filter(VersionRemoval::pending).isEmpty()) {
                return null;
            }
            // Page views first: each names its picture file.
            materials.deletePageViews(versionId);
            materials.deletePassages(versionId);
            materials.deleteOutline(versionId);
            materials.deletePageRanges(versionId);
            return materials.versionFiles(versionId);
        });
        if (stored == null) {
            return false;
        }
        for (VersionFileRow file : stored) {
            files.deleteFile(file.ownerId(), file.fileId());
        }
        Set<UUID> removed = new HashSet<>();
        stored.forEach(file -> removed.add(file.fileId()));
        List<VersionFileRow> left = template.execute(status -> {
            if (materials.lockVersion(versionId).filter(VersionRemoval::pending).isEmpty()) {
                return null;
            }
            Instant now = now();
            List<VersionFileRow> rows = materials.deleteVersionFiles(versionId);
            ingestion.removeRawUpload(owner, versionId, now);
            materials.clearRemovalPending(versionId, now);
            return rows.stream().filter(file -> !removed.contains(file.fileId())).toList();
        });
        if (left == null) {
            return false;
        }
        // A file recorded after the first part (none is expected: a discarded version records no report).
        left.forEach(file -> files.deleteFile(file.ownerId(), file.fileId()));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", versionId)
                .addKeyValue("files", stored.size() + left.size()).log("material.version_removed");
        return true;
    }

    private static boolean pending(VersionRow version) {
        return version.removalPending() && Uploads.DISCARDED.equals(version.state());
    }

    private Instant now() {
        return clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS);
    }
}
