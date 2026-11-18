package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Discards uploads left unfinished (Story 1.13): every {@code gmshield.materials.upload-sweep-every}
 * ({@link UploadSweepSchedule}). A removal has three steps, each safe to repeat, so a stop at any point is finished by
 * the next sweep:
 *
 * <ol>
 *   <li>as the upload's owner ({@link OwnerContext#callAs}), in one transaction, the version becomes
 *       {@code discarded} and the upload {@code removing} (only an open upload past its expiry with no live lease);</li>
 *   <li>the file store removes the upload's pieces;</li>
 *   <li>the {@code upload} row is deleted.</li>
 * </ol>
 *
 * From Story 1.21 an expired member of an image set fails alone instead ({@code upload.expired}: its pieces go, its row
 * and its set's version stay, for the GM to replace or leave out). A set that nobody can finish any more is discarded:
 * no member open, one at least failed, and every failed member past its expiry (complete members do not keep it). Its
 * version becomes {@code discarded} and every member, complete ones included, {@code removing}, together, then
 * finished as above: after 6 hours an unfinished upload is gone.
 *
 * <p>The sweep first finishes every {@code removing} row, then starts on the expired ones, then on abandoned sets. It reads and deletes rows in
 * transactions of its own ({@link UploadStorage#separately}): it has no owner, so it cannot open the application's
 * transaction. One failed upload never stops the others; it is logged by error type and tried again next time.
 */
@Service
public class UploadSweep {

    static final int BATCH = 100;
    private static final Logger log = LoggerFactory.getLogger(UploadSweep.class);

    private final UploadStorage uploads;
    private final MaterialStore materials;
    private final Uploads service;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<Clock> clock;

    UploadSweep(UploadStorage uploads, MaterialStore materials, Uploads service,
                ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.uploads = uploads;
        this.materials = materials;
        this.service = service;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** One sweep: how many uploads it removed. Never throws. */
    public int sweep() {
        int removed = 0;
        try {
            for (UploadRow row : uploads.separately(store -> store.removing(BATCH))) {
                removed += finish(row) ? 1 : 0;
            }
            Instant now = clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS);
            List<UploadRow> expired = uploads.separately(store -> store.expired(now, BATCH));
            for (UploadRow row : expired) {
                if (row.member()) {
                    expireMember(row, now);
                } else if (start(row, now)) {
                    removed += finish(row) ? 1 : 0;
                }
            }
            for (UploadRow row : uploads.separately(store -> store.abandonedSets(now, BATCH))) {
                for (UploadRow member : abandon(row, now)) {
                    removed += finish(member) ? 1 : 0;
                }
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("upload.sweep_failed");
        }
        return removed;
    }

    /** Step 1, as the owner: the version {@code discarded} and the upload {@code removing}, together or not at all. */
    boolean start(UploadRow row, Instant now) {
        try {
            Boolean started = OwnerContext.callAs(row.ownerId(), () ->
                    new TransactionTemplate(transactions.getObject()).execute(status -> {
                        if (!uploads.joined().removeExpired(row.id(), now)) {
                            return false;
                        }
                        materials.changeVersion(row.versionId(), Uploads.DISCARDED, null, null, now);
                        return true;
                    }));
            if (Boolean.TRUE.equals(started)) {
                log.atInfo().addKeyValue("ownerId", row.ownerId()).addKeyValue("uploadId", row.id())
                        .addKeyValue("versionId", row.versionId()).log("upload.expired");
                return true;
            }
            return false;
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("uploadId", row.id()).addKeyValue("errorType", failure.getClass().getName())
                    .log("upload.removal_failed");
            return false;
        }
    }

    /** An expired member of an image set fails alone (Story 1.21), as its owner; then its pieces go. */
    private void expireMember(UploadRow row, Instant now) {
        try {
            Boolean failed = OwnerContext.callAs(row.ownerId(), () ->
                    new TransactionTemplate(transactions.getObject()).execute(status ->
                            uploads.joined().failExpired(row.id(), UploadProblems.EXPIRED_CODE, now)));
            if (Boolean.TRUE.equals(failed)) {
                log.atInfo().addKeyValue("ownerId", row.ownerId()).addKeyValue("uploadId", row.id())
                        .addKeyValue("versionId", row.versionId()).log("upload.image_expired");
                service.removePieces(row);
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("uploadId", row.id()).addKeyValue("errorType", failure.getClass().getName())
                    .log("upload.removal_failed");
        }
    }

    /**
     * A set nobody can finish any more (Story 1.21), as its owner, in one transaction with its version locked: when its
     * version is still uploading, no member is open, one at least has failed and every failed member has expired, the
     * version becomes {@code discarded} and every member, complete ones included, {@code removing}. Answers those
     * members, whose removal is then finished.
     */
    private List<UploadRow> abandon(UploadRow row, Instant now) {
        try {
            List<UploadRow> members = OwnerContext.callAs(row.ownerId(), () ->
                    new TransactionTemplate(transactions.getObject()).execute(status -> {
                        Optional<VersionRow> version = materials.lockVersion(row.versionId())
                                .filter(found -> Uploads.UPLOADING.equals(found.state()));
                        List<UploadRow> found = uploads.joined().ofVersion(row.ownerId(), row.versionId());
                        if (version.isEmpty() || !abandoned(found, now)) {
                            return List.<UploadRow>of();
                        }
                        materials.changeVersion(row.versionId(), Uploads.DISCARDED, null, null, now);
                        return found.stream().filter(member -> UploadRow.FAILED.equals(member.state())
                                ? uploads.joined().removeFailed(member.id(), now)
                                : uploads.joined().removeComplete(member.id(), now)).toList();
                    }));
            if (!members.isEmpty()) {
                log.atInfo().addKeyValue("ownerId", row.ownerId()).addKeyValue("versionId", row.versionId())
                        .addKeyValue("images", members.size()).log("upload.set_abandoned");
            }
            return members;
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("versionId", row.versionId()).addKeyValue("errorType", failure.getClass().getName())
                    .log("upload.removal_failed");
            return List.of();
        }
    }

    /** No member open, one at least failed, every failed one past its expiry. */
    private static boolean abandoned(List<UploadRow> members, Instant now) {
        return members.stream().noneMatch(member -> UploadRow.OPEN.equals(member.state())
                || (UploadRow.FAILED.equals(member.state()) && member.expiresAt().isAfter(now)))
                && members.stream().anyMatch(member -> UploadRow.FAILED.equals(member.state()));
    }

    /** Steps 2 and 3. */
    private boolean finish(UploadRow row) {
        try {
            service.finishRemoval(row);
            return true;
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("uploadId", row.id()).addKeyValue("errorType", failure.getClass().getName())
                    .log("upload.removal_failed");
            return false;
        }
    }
}
