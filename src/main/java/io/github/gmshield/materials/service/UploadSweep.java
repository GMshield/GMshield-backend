package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * The sweep first finishes every {@code removing} row, then starts on the expired ones. It reads and deletes rows in
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
                if (start(row, now)) {
                    removed += finish(row) ? 1 : 0;
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
