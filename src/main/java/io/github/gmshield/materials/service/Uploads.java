package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Sha256;
import io.github.gmshield.vault.service.Vault;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
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
 * The resumable upload of a material's file (Story 1.13, AD-16): the backend's own tus 1.0.0 server, for the owner of
 * the work ({@link OwnerContext}).
 *
 * <ul>
 *   <li>{@link #create}: a material (its name encrypted), a version in {@code uploading} and an upload, in one
 *       transaction. The upload expires 6 hours after its creation, whatever happens meanwhile.</li>
 *   <li>{@link #patch}: one part at a time, under a lease. The first part's first 4 KiB (the whole file when it is
 *       shorter) decide the format before anything is written: a first part shorter than that is refused as invalid,
 *       and a refused format leaves no row and no piece. The part streams through a SHA-256 into
 *       4 MiB pieces, each encrypted into the file store; it counts only when its checksum matches.</li>
 *   <li>{@link #complete}: the stored pieces are decrypted one at a time and hashed; a match makes the version
 *       {@code waiting}, a mismatch discards it and removes the pieces.</li>
 * </ul>
 *
 * <p>Another owner's upload, an unknown one and one being removed are all "not found". Nothing here logs a name or a
 * hash; the file is never held whole in memory.
 */
@Service
public class Uploads {

    /** The largest file: 2 GiB. */
    public static final long MAX_LENGTH = 2L * 1024 * 1024 * 1024;
    /** The largest part one PATCH may carry: 90 MiB, under the tunnel's 100 MB request limit. */
    public static final long MAX_PART = 90L * 1024 * 1024;
    /** The size of a stored piece, before encryption. */
    static final int PIECE_BYTES = 4 * 1024 * 1024;
    static final Duration LIFETIME = Duration.ofHours(6);
    static final Duration LEASE = Duration.ofMinutes(2);
    static final String MATERIAL = "material";
    static final String FIELD_NAME = "name";
    static final String UPLOADING = "uploading";
    static final String WAITING = "waiting";
    static final String DISCARDED = "discarded";
    /** The longest name a material may have, in characters. */
    static final int MAX_NAME = 255;
    private static final Logger log = LoggerFactory.getLogger(Uploads.class);

    private final MaterialStore materials;
    private final UploadStorage uploads;
    private final Vault vault;
    private final FileStore files;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<Clock> clock;

    Uploads(MaterialStore materials, UploadStorage uploads, Vault vault, FileStore files,
            ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.materials = materials;
        this.uploads = uploads;
        this.vault = vault;
        this.files = files;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** A new upload: its id and when it expires. */
    public record Created(UUID uploadId, Instant expiresAt) {
    }

    /** Where an upload stands: the verified offset, its length and its expiry. */
    public record Progress(long offset, long length, Instant expiresAt) {
    }

    /** A verified file: its material, its version and the version's state. */
    public record Completed(UUID materialId, UUID versionId, String state) {
    }

    /** Creates the material, its version and the upload of {@code length} bytes named {@code name}. */
    public Created create(long length, String name) {
        UUID owner = OwnerContext.require();
        if (length < 1 || length > MAX_LENGTH) {
            throw UploadProblems.tooLarge(MAX_LENGTH);
        }
        String material = validName(name);
        Instant now = now();
        UUID materialId = Ids.newId();
        UUID versionId = Ids.newId();
        UUID uploadId = Ids.newId();
        Instant expiresAt = now.plus(LIFETIME);
        inTransaction(() -> {
            materials.insertMaterial(new MaterialRow(materialId, owner,
                    vault.encryptText(new Binding(MATERIAL, FIELD_NAME, materialId, owner), material), now));
            materials.insertVersion(new VersionRow(versionId, owner, materialId, UPLOADING, null, null, now, now));
            uploads.joined().insert(new UploadRow(uploadId, owner, versionId, length, 0, 0, null, expiresAt, null, null,
                    UploadRow.OPEN, now, now));
            return null;
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("uploadId", uploadId).addKeyValue("materialId", materialId)
                .addKeyValue("versionId", versionId).addKeyValue("length", length).log("upload.created");
        return new Created(uploadId, expiresAt);
    }

    /** The verified offset of the owner's upload. */
    public Progress head(UUID id) {
        UploadRow row = usable(id);
        return new Progress(row.offset(), row.length(), row.expiresAt());
    }

    /**
     * Receives one part starting at {@code offset}: {@code declaredLength} bytes (-1 when the request did not say),
     * whose SHA-256 must be {@code checksum}. Answers the new verified offset.
     */
    public Progress patch(UUID id, long offset, long declaredLength, byte[] checksum, InputStream body) {
        UUID owner = OwnerContext.require();
        UploadRow row = usable(id);
        if (UploadRow.COMPLETE.equals(row.state())) {
            if (offset != row.length()) {
                throw UploadProblems.offsetConflict();
            }
            if (declaredLength > 0) {
                throw UploadProblems.tooLarge(MAX_PART);
            }
            return new Progress(row.length(), row.length(), row.expiresAt());
        }
        if (offset != row.offset()) {
            throw UploadProblems.offsetConflict();
        }
        long allowed = Math.min(MAX_PART, row.length() - offset);
        if (declaredLength > allowed) {
            throw UploadProblems.tooLarge(MAX_PART);
        }
        Lease lease = takeLease(row, owner);
        try {
            return receive(lease, row, owner, offset, allowed, checksum, body);
        } finally {
            lease.release();
        }
    }

    /** Verifies the whole file against {@code sha256} and hands the version on as {@code waiting}. */
    public Completed complete(UUID id, byte[] sha256) {
        UUID owner = OwnerContext.require();
        UploadRow row = usable(id);
        if (UploadRow.COMPLETE.equals(row.state())) {
            // Already verified: the answer was lost, and this is the same request again.
            VersionRow version = reading(() -> materials.findVersion(row.versionId()))
                    .orElseThrow(UploadProblems::notFound);
            return new Completed(version.materialId(), version.id(), version.state());
        }
        if (row.offset() != row.length()) {
            throw UploadProblems.incomplete();
        }
        Lease lease = takeLease(row, owner);
        VersionRow version;
        try {
            boolean matches = wholeFileMatches(lease, row, owner, sha256);
            Instant now = now();
            if (!matches) {
                inTransaction(() -> {
                    if (!uploads.joined().removeHeld(row.id(), lease.token, now)) {
                        throw lost(row.id());
                    }
                    materials.changeVersion(row.versionId(), DISCARDED, null, null, now);
                    return null;
                });
                lease.released = true;
                log.atInfo().addKeyValue("ownerId", owner).addKeyValue("uploadId", row.id())
                        .addKeyValue("versionId", row.versionId()).log("upload.file_checksum_mismatch");
                finishRemoval(row);
                throw UploadProblems.fileChecksumMismatch();
            }
            version = inTransaction(() -> {
                if (!uploads.joined().complete(row.id(), lease.token, now)) {
                    throw lost(row.id());
                }
                materials.changeVersion(row.versionId(), WAITING, row.format(), row.length(), now);
                return materials.findVersion(row.versionId()).orElseThrow(UploadProblems::notFound);
            });
            lease.released = true;
        } finally {
            lease.release();
        }
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("uploadId", row.id())
                .addKeyValue("versionId", version.id()).addKeyValue("format", row.format()).log("upload.completed");
        return new Completed(version.materialId(), version.id(), version.state());
    }

    /**
     * The last two steps of a removal, each safe to repeat: the pieces go, then the row. The first step (the version
     * {@code discarded}, the upload {@code removing}) has committed before this runs; the sweep finishes any removal
     * that stopped half way.
     */
    void finishRemoval(UploadRow row) {
        files.deleteFile(row.ownerId(), row.id());
        uploads.separately(store -> store.deleteRemoving(row.id()));
        log.atInfo().addKeyValue("ownerId", row.ownerId()).addKeyValue("uploadId", row.id()).log("upload.removed");
    }

    // --- a part ---

    private Progress receive(Lease lease, UploadRow row, UUID owner, long offset, long allowed, byte[] checksum,
                             InputStream body) {
        Sha256 digest = vault.sha256();
        byte[] buffer = new byte[PIECE_BYTES];
        int filled = 0;
        String format = null;
        if (offset == 0) {
            filled = read(body, buffer, 0, (int) Math.min(FormatSniffer.SAMPLE_BYTES, allowed + 1));
            if (filled > allowed) {
                throw UploadProblems.tooLarge(MAX_PART);
            }
            if (filled < Math.min(FormatSniffer.SAMPLE_BYTES, row.length())) {
                // Too few bytes to tell the format: nothing is written or deleted, the upload stays as it was.
                throw UploadProblems.invalid();
            }
            format = FormatSniffer.sniff(buffer, filled, filled < row.length(), name(row))
                    .map(Format::code).orElse(null);
            if (format == null) {
                refuse(row, owner);
                throw UploadProblems.formatRefused();
            }
        }
        long received = filled;
        int pieces = 0;
        int count;
        do {
            count = read(body, buffer, filled, buffer.length - filled);
            filled += count;
            received += count;
            if (received > allowed) {
                throw UploadProblems.tooLarge(MAX_PART);
            }
            if (filled == buffer.length || (count == 0 && filled > 0)) {
                digest.update(buffer, 0, filled);
                lease.renew();
                files.writePiece(owner, row.id(), row.pieceCount() + pieces,
                        filled == buffer.length ? buffer : Arrays.copyOf(buffer, filled));
                pieces++;
                filled = 0;
            }
        } while (count > 0);
        if (!digest.matches(checksum)) {
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("uploadId", row.id()).addKeyValue("offset", offset)
                    .log("upload.part_checksum_mismatch");
            throw UploadProblems.partChecksumMismatch();
        }
        if (received == 0) {
            return new Progress(offset, row.length(), row.expiresAt());
        }
        long next = offset + received;
        int pieceCount = row.pieceCount() + pieces;
        String decided = format;
        boolean counted = Boolean.TRUE.equals(inTransaction(() ->
                uploads.joined().advance(row.id(), lease.token, offset, next, pieceCount, decided, now())));
        if (!counted) {
            throw lost(row.id());
        }
        lease.released = true;
        return new Progress(next, row.length(), row.expiresAt());
    }

    /** Fills {@code buffer} from {@code start} with up to {@code wanted} bytes; 0 at the end of the body. */
    private static int read(InputStream body, byte[] buffer, int start, int wanted) {
        try {
            return body.readNBytes(buffer, start, wanted);
        } catch (IOException cut) {
            // The body broke off (the client or the relay went away): nothing of this part counts.
            throw UploadProblems.invalid();
        }
    }

    /** A refused format: the upload, its version and its material go in one transaction, then any piece. */
    private void refuse(UploadRow row, UUID owner) {
        inTransaction(() -> {
            VersionRow version = materials.findVersion(row.versionId()).orElseThrow(UploadProblems::notFound);
            uploads.joined().delete(row.id());
            materials.deleteVersion(version.id());
            materials.deleteMaterial(version.materialId());
            return null;
        });
        files.deleteFile(owner, row.id());
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("uploadId", row.id()).log("upload.format_refused");
    }

    /** The material's name, decrypted: it tells Markdown from plain text, and nothing else. */
    private String name(UploadRow row) {
        return reading(() -> materials.findVersion(row.versionId())
                .flatMap(version -> materials.findMaterial(version.materialId())))
                .map(material -> vault.decryptText(new Binding(MATERIAL, FIELD_NAME, material.id(), material.ownerId()),
                        material.name()))
                .orElseThrow(UploadProblems::notFound);
    }

    // --- the whole file ---

    private boolean wholeFileMatches(Lease lease, UploadRow row, UUID owner, byte[] expected) {
        Sha256 digest = vault.sha256();
        long total = 0;
        for (int index = 0; index < row.pieceCount(); index++) {
            lease.renew();
            byte[] piece = files.readPiece(owner, row.id(), index);
            digest.update(piece, 0, piece.length);
            total += piece.length;
        }
        return total == row.length() && digest.matches(expected);
    }

    // --- rows, leases and transactions ---

    /** The owner's upload that a request may use: open and unexpired, or complete. */
    private UploadRow usable(UUID id) {
        UUID owner = OwnerContext.require();
        UploadRow row = uploads.joined().find(id)
                .filter(found -> found.ownerId().equals(owner))
                .filter(found -> !UploadRow.REMOVING.equals(found.state()))
                .orElseThrow(UploadProblems::notFound);
        if (UploadRow.OPEN.equals(row.state()) && !now().isBefore(row.expiresAt())) {
            throw UploadProblems.expired();
        }
        return row;
    }

    private Lease takeLease(UploadRow row, UUID owner) {
        UUID token = UUID.randomUUID();
        Instant now = now();
        if (uploads.joined().takeLease(row.id(), owner, row.offset(), token, now.plus(LEASE), now)) {
            return new Lease(row.id(), token);
        }
        UploadRow current = usable(row.id());
        if (current.offset() != row.offset() || !UploadRow.OPEN.equals(current.state())) {
            throw UploadProblems.offsetConflict();
        }
        throw UploadProblems.busy();
    }

    /** The lease was lost while the request worked: the upload expired and was removed, or another request took it. */
    private ApiException lost(UUID id) {
        UploadRow current = uploads.joined().find(id).orElse(null);
        if (current == null || !UploadRow.OPEN.equals(current.state()) || !now().isBefore(current.expiresAt())) {
            return UploadProblems.expired();
        }
        return UploadProblems.busy();
    }

    /** The right to write one upload, for one request. */
    private final class Lease {
        private final UUID id;
        private final UUID token;
        private boolean released;

        Lease(UUID id, UUID token) {
            this.id = id;
            this.token = token;
        }

        void renew() {
            Instant now = now();
            if (!uploads.joined().renewLease(id, token, now.plus(LEASE), now)) {
                released = true;
                throw lost(id);
            }
        }

        void release() {
            if (!released) {
                released = true;
                try {
                    uploads.joined().releaseLease(id, token, now());
                } catch (RuntimeException failure) {
                    // It lapses on its own within the lease time.
                    log.atWarn().addKeyValue("uploadId", id).addKeyValue("errorType", failure.getClass().getName())
                            .log("upload.lease_not_released");
                }
            }
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions.getObject()).execute(status -> work.get());
    }

    private <T> Optional<T> reading(Supplier<Optional<T>> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    private Instant now() {
        return clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** A material's name: trimmed, 1 to {@value #MAX_NAME} characters, with no control character. */
    static String validName(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length()) > MAX_NAME
                || stripped.codePoints().anyMatch(Character::isISOControl)) {
            throw UploadProblems.metadataInvalid();
        }
        return stripped;
    }
}
