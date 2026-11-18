package io.github.gmshield.materials.service;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.MaterialSystemRow;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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
 *   <li>{@link #create}: a material (its name encrypted, with what the GM said about it: {@link Details}), a version in
 *       {@code uploading} and an upload, in one transaction. The upload expires 6 hours after its creation, whatever
 *       happens meanwhile.</li>
 *   <li>{@link #patch}: one part at a time, under a lease. The first part's first 4 KiB (the whole file when it is
 *       shorter) decide the format before anything is written: a first part shorter than that is refused as invalid,
 *       and a refused format leaves no row and no piece. The part streams through a SHA-256 into
 *       4 MiB pieces, each encrypted into the file store; it counts only when its checksum matches.</li>
 *   <li>{@link #complete}: the stored pieces are decrypted one at a time and hashed; a match makes the version
 *       {@code waiting} and, in the same transaction, starts its ingestion chain ({@link IngestionStart}, Story 1.15);
 *       a mismatch discards it and removes the pieces. From Story 1.21 an image's version has the format
 *       {@value Format#IMAGES}: an image alone is a set of one.</li>
 * </ul>
 *
 * <p>From Story 1.21, a member of an image set ({@link ImageSets}) is an upload like any other, with three
 * differences: its format must be an image's; a refused format, a whole-file mismatch or an expiry fails the member
 * alone (its pieces go, its row stays {@code failed} with the reason) and never the set's version; and a verified
 * member starts the set's chain only once no member is open or failed ({@link #startIfReady}), with the version
 * locked so that two members completing at once start it once.
 *
 * <p>Another owner's upload, an unknown one and one being removed are all "not found". Nothing here logs a name, a
 * hash, a kind or where a material belongs; the file is never held whole in memory.
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
    /** The most images an image set may hold (Story 1.21); its bytes in all are at most {@link #MAX_LENGTH}. */
    public static final int MAX_IMAGES = 500;
    /** The longest name a material may have, in characters. */
    static final int MAX_NAME = 255;
    /** What a material may be (Story 1.14). */
    public static final Set<String> KINDS = Set.of("rulebook", "supplement", "homebrew", "campaign_notes", "other");
    /** The languages a GM may name; {@value #DETECT} leaves it to a later read stage and is stored as null. */
    public static final Set<String> LANGUAGES = Set.of("pt-BR", "en");
    public static final String DETECT = "detect";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Logger log = LoggerFactory.getLogger(Uploads.class);

    private final MaterialStore materials;
    private final UploadStorage uploads;
    private final Vault vault;
    private final FileStore files;
    private final Library library;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<Clock> clock;
    private final IngestionStart ingestion;

    Uploads(MaterialStore materials, UploadStorage uploads, Vault vault, FileStore files, Library library,
            ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock,
            IngestionStart ingestion) {
        this.ingestion = ingestion;
        this.materials = materials;
        this.uploads = uploads;
        this.vault = vault;
        this.files = files;
        this.library = library;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * What the GM said about a file (Story 1.14), as the tus metadata gave it: each may be null (an older frontend sends
     * none). {@code kind} is one of {@link #KINDS}; {@code language} one of {@link #LANGUAGES} or {@value #DETECT};
     * {@code campaign} or {@code system} the id of one of the owner's, never both. {@code toString} shows nothing.
     */
    public record Details(String kind, String language, String campaign, String system) {

        public static final Details NONE = new Details(null, null, null, null);

        @Override
        public String toString() {
            return "Details[]";
        }
    }

    /** The details once checked: the language null when left to detection. */
    record Checked(String kind, String language, UUID campaign, UUID system) {
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

    /** Creates the material, its version and the upload of {@code length} bytes named {@code name}, with no details. */
    public Created create(long length, String name) {
        return create(length, name, Details.NONE);
    }

    /**
     * Creates the material with its details, its version and the upload of {@code length} bytes named {@code name}. A
     * detail that is not valid, or a campaign or system that is not the owner's, is {@code upload.metadata_invalid},
     * which never says which, and nothing is stored.
     */
    public Created create(long length, String name, Details details) {
        UUID owner = OwnerContext.require();
        if (length < 1 || length > MAX_LENGTH) {
            throw UploadProblems.tooLarge(MAX_LENGTH);
        }
        String material = validName(name);
        Checked checked = checked(details == null ? Details.NONE : details);
        Instant now = now();
        UUID materialId = Ids.newId();
        UUID versionId = Ids.newId();
        UUID uploadId = Ids.newId();
        Instant expiresAt = now.plus(LIFETIME);
        inTransaction(() -> {
            insertMaterial(owner, materialId, versionId, material, checked, now);
            uploads.joined().insert(new UploadRow(uploadId, owner, versionId, length, 0, 0, null, expiresAt, null, null,
                    UploadRow.OPEN, now, now));
            return null;
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("uploadId", uploadId).addKeyValue("materialId", materialId)
                .addKeyValue("versionId", versionId).addKeyValue("length", length).log("upload.created");
        return new Created(uploadId, expiresAt);
    }

    /**
     * The material (its name encrypted, its details) and its version in {@code uploading}, in the caller's transaction
     * (Stories 1.13, 1.14 and 1.21).
     */
    void insertMaterial(UUID owner, UUID materialId, UUID versionId, String name, Checked checked, Instant now) {
        materials.insertMaterial(new MaterialRow(materialId, owner,
                vault.encryptText(new Binding(MATERIAL, FIELD_NAME, materialId, owner), name), now,
                checked.kind(), checked.language(), checked.campaign()));
        if (checked.system() != null) {
            materials.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), owner, materialId, checked.system(), now));
        }
        materials.insertVersion(new VersionRow(versionId, owner, materialId, UPLOADING, null, null, now, now));
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
            if (!matches && row.member()) {
                failMember(lease, row, owner, UploadProblems.FILE_CHECKSUM_MISMATCH_CODE, now);
                throw UploadProblems.fileChecksumMismatch();
            }
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
                // A member's set is decided with its version locked: two members completing at once start it once.
                Optional<VersionRow> locked = row.member() ? materials.lockVersion(row.versionId()) : Optional.empty();
                if (!uploads.joined().complete(row.id(), lease.token, now)) {
                    throw lost(row.id());
                }
                if (row.member()) {
                    return startIfReady(locked.orElseThrow(UploadProblems::notFound), now);
                }
                // An image alone is a set of one (Story 1.21).
                String format = Format.image(row.format()) ? Format.IMAGES : row.format();
                materials.changeVersion(row.versionId(), WAITING, format, row.length(), now);
                VersionRow waiting = materials.findVersion(row.versionId()).orElseThrow(UploadProblems::notFound);
                ingestion.start(waiting, row);
                return waiting;
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
     * Starts an image set (Story 1.21) once it is whole, in the caller's transaction with {@code version} locked: when
     * the version is still {@code uploading}, no member is open or failed and one at least is complete, it becomes
     * {@code waiting} with the format {@value Format#IMAGES} and the size of its members, and its first step reads them
     * in order. Answers the version as it is now.
     */
    VersionRow startIfReady(VersionRow version, Instant now) {
        if (!UPLOADING.equals(version.state())) {
            return version;
        }
        List<UploadRow> members = uploads.joined().ofVersion(version.ownerId(), version.id());
        if (members.isEmpty() || members.stream().anyMatch(member -> !UploadRow.COMPLETE.equals(member.state()))) {
            return version;
        }
        long size = members.stream().mapToLong(UploadRow::length).sum();
        materials.changeVersion(version.id(), WAITING, Format.IMAGES, size, now);
        VersionRow waiting = materials.findVersion(version.id()).orElseThrow(UploadProblems::notFound);
        ingestion.start(waiting, members.getFirst());
        log.atInfo().addKeyValue("ownerId", version.ownerId()).addKeyValue("versionId", version.id())
                .addKeyValue("images", members.size()).addKeyValue("length", size).log("upload.set_started");
        return waiting;
    }

    /**
     * A held member fails (Story 1.21) with {@code code}: the row stays {@code failed}, its pieces go, and the set's
     * version is kept.
     */
    private void failMember(Lease lease, UploadRow row, UUID owner, String code, Instant now) {
        inTransaction(() -> {
            if (!uploads.joined().failHeld(row.id(), lease.token, code, now)) {
                throw lost(row.id());
            }
            return null;
        });
        lease.released = true;
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("uploadId", row.id())
                .addKeyValue("versionId", row.versionId()).addKeyValue("code", code).log("upload.image_failed");
        removePieces(row);
    }

    /**
     * The pieces of a failed member go (Story 1.21); a failure here leaves them to the removal of its row (replaced,
     * left out or swept), which removes them again.
     */
    void removePieces(UploadRow row) {
        try {
            files.deleteFile(row.ownerId(), row.id());
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("uploadId", row.id()).addKeyValue("errorType", failure.getClass().getName())
                    .log("upload.pieces_removal_deferred");
        }
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
            if (row.member() && !Format.image(format)) {
                // A member of an image set must be an image: it alone fails, and the set waits for the GM.
                failMember(lease, row, owner, UploadProblems.FORMAT_REFUSED_CODE, now());
                throw UploadProblems.imageFormatRefused();
            }
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

    /**
     * The file's name, decrypted: it tells Markdown from plain text, and nothing else. A member's own name (Story 1.21),
     * else the material's.
     */
    private String name(UploadRow row) {
        if (row.member()) {
            return vault.decryptText(memberName(row.id(), row.ownerId()), row.name());
        }
        return reading(() -> materials.findVersion(row.versionId())
                .flatMap(version -> materials.findMaterial(version.materialId())))
                .map(material -> vault.decryptText(new Binding(MATERIAL, FIELD_NAME, material.id(), material.ownerId()),
                        material.name()))
                .orElseThrow(UploadProblems::notFound);
    }

    /** The vault binding of a member's name (Story 1.21): the upload row's own. */
    static Binding memberName(UUID uploadId, UUID owner) {
        return new Binding(UploadRow.ENTITY, UploadRow.FIELD_NAME, uploadId, owner);
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

    /** The owner's upload that a request may use: open and unexpired, or complete; never a failed member. */
    private UploadRow usable(UUID id) {
        UUID owner = OwnerContext.require();
        UploadRow row = uploads.joined().find(id)
                .filter(found -> found.ownerId().equals(owner))
                .filter(found -> !UploadRow.REMOVING.equals(found.state()) && !UploadRow.FAILED.equals(found.state()))
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

    <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions.getObject()).execute(status -> work.get());
    }

    private <T> Optional<T> reading(Supplier<Optional<T>> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    Instant now() {
        return clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The details checked against the owner's library: a campaign or a system must be the owner's (another owner's id
     * reads like an unknown one). The check comes before the transaction; the database's owner-scoped keys still hold
     * should the item go meanwhile.
     */
    Checked checked(Details details) {
        if (details.kind() != null && !KINDS.contains(details.kind())) {
            throw UploadProblems.metadataInvalid();
        }
        String language = details.language();
        if (language != null && !language.equals(DETECT) && !LANGUAGES.contains(language)) {
            throw UploadProblems.metadataInvalid();
        }
        if (details.campaign() != null && details.system() != null) {
            throw UploadProblems.metadataInvalid();
        }
        UUID campaign = id(details.campaign());
        UUID system = id(details.system());
        if (campaign != null && library.campaign(campaign).isEmpty()) {
            throw UploadProblems.metadataInvalid();
        }
        if (system != null && library.system(system).isEmpty()) {
            throw UploadProblems.metadataInvalid();
        }
        return new Checked(details.kind(), DETECT.equals(language) ? null : language, campaign, system);
    }

    private static UUID id(String text) {
        if (text == null) {
            return null;
        }
        if (!UUID_TEXT.matcher(text).matches()) {
            throw UploadProblems.metadataInvalid();
        }
        return UUID.fromString(text);
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
