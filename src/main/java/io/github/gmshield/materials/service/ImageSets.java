package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Photos as the pages of one material (Story 1.21, FR-13): an ordered set of images, for the owner of the work
 * ({@link OwnerContext}). The backend mints the set (its id is its version's), its version and the upload of each
 * image, and owns the order, which the GM fixed when sending ("Enviar" confirms it): it never changes afterwards. Each
 * image then goes up as its own tus upload ({@link Uploads}), with the usual part and whole-file checks and its 6 hours.
 *
 * <ul>
 *   <li>{@link #create}: in one transaction, the material (its name encrypted, its details), its version in
 *       {@code uploading} and one open upload per image at its place, each image's name encrypted bound to its upload
 *       row. At most {@value Uploads#MAX_IMAGES} images and {@link Uploads#MAX_LENGTH} bytes in all
 *       ({@code upload.set_too_large}, 413).</li>
 *   <li>A member that fails (its format, its whole-file check, its expiry) fails alone; the GM then either
 *       {@link #replace}s it (a new open upload at the same place, checked again) or {@link #drop}s it (the places
 *       after it move up by one; the last one dropped discards the version). Once no member is open and every failed
 *       one has expired, the upload sweep discards the set, its complete members included ({@link UploadSweep}).</li>
 *   <li>The set starts by itself once no member is open or failed and one at least is complete
 *       ({@link Uploads#startIfReady}), after a member's completion or a drop.</li>
 * </ul>
 *
 * Another owner's set, an unknown one and a set that is no longer uploading are all "not found"; a place whose image
 * has not failed is {@code upload.image_not_failed} (409). Nothing here logs a name.
 */
@Service
public class ImageSets {

    private static final Logger log = LoggerFactory.getLogger(ImageSets.class);

    private final MaterialStore materials;
    private final UploadStorage uploads;
    private final Vault vault;
    private final Uploads service;

    ImageSets(MaterialStore materials, UploadStorage uploads, Vault vault, Uploads service) {
        this.materials = materials;
        this.uploads = uploads;
        this.vault = vault;
        this.service = service;
    }

    /** One image of a set as the GM chose it: its file name and its size in bytes. {@code toString} shows the size. */
    public record Image(String name, long length) {
        @Override
        public String toString() {
            return "Image[length=" + length + "]";
        }
    }

    /** An image's place in its set and its upload's id. */
    public record Member(int position, UUID uploadId) {
    }

    /** A new set: its id (its version's), its material, when its uploads expire, and its images in order. */
    public record Created(UUID setId, UUID materialId, Instant expiresAt, List<Member> images) {
    }

    /** A new upload at a failed image's place, and when it expires. */
    public record Replaced(int position, UUID uploadId, Instant expiresAt) {
    }

    /**
     * Creates the material named {@code name} with its details, its version and one open upload per image, in this
     * order. Nothing is stored when a check fails.
     */
    public Created create(String name, Uploads.Details details, List<Image> images) {
        UUID owner = OwnerContext.require();
        if (images == null || images.isEmpty()) {
            throw UploadProblems.invalid();
        }
        if (images.size() > Uploads.MAX_IMAGES) {
            throw UploadProblems.setTooLarge();
        }
        long total = 0;
        List<String> names = new ArrayList<>(images.size());
        for (Image image : images) {
            names.add(checked(image));
            total += image.length();
        }
        if (total > Uploads.MAX_LENGTH) {
            throw UploadProblems.setTooLarge();
        }
        String material = Uploads.validName(name);
        Uploads.Checked checked = service.checked(details == null ? Uploads.Details.NONE : details);
        Instant now = service.now();
        Instant expiresAt = now.plus(Uploads.LIFETIME);
        UUID materialId = Ids.newId();
        UUID versionId = Ids.newId();
        List<Member> members = new ArrayList<>(images.size());
        service.inTransaction(() -> {
            service.insertMaterial(owner, materialId, versionId, material, checked, now);
            for (int index = 0; index < images.size(); index++) {
                UUID uploadId = Ids.newId();
                uploads.joined().insert(member(uploadId, owner, versionId, images.get(index).length(), index + 1,
                        names.get(index), expiresAt, now));
                members.add(new Member(index + 1, uploadId));
            }
            return null;
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("materialId", materialId)
                .addKeyValue("versionId", versionId).addKeyValue("images", images.size()).addKeyValue("length", total)
                .log("upload.set_created");
        return new Created(versionId, materialId, expiresAt, List.copyOf(members));
    }

    /**
     * A new open upload for the failed image at {@code position} of the owner's set: the failed one goes (its pieces
     * and row after the commit), and the set's bytes in all stay within {@link Uploads#MAX_LENGTH}.
     */
    public Replaced replace(UUID setId, int position, Image image) {
        UUID owner = OwnerContext.require();
        String name = checked(image);
        Instant now = service.now();
        Instant expiresAt = now.plus(Uploads.LIFETIME);
        UUID uploadId = Ids.newId();
        UploadRow failed = service.inTransaction(() -> {
            List<UploadRow> members = members(setId);
            UploadRow at = failedAt(members, position);
            long others = members.stream().filter(member -> !member.id().equals(at.id()))
                    .filter(member -> !UploadRow.FAILED.equals(member.state())).mapToLong(UploadRow::length).sum();
            if (others + image.length() > Uploads.MAX_LENGTH) {
                throw UploadProblems.setTooLarge();
            }
            if (!uploads.joined().removeFailed(at.id(), now)) {
                throw UploadProblems.notFound();
            }
            uploads.joined().insert(member(uploadId, owner, setId, image.length(), position, name, expiresAt, now));
            return at;
        });
        finish(failed);
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", setId).addKeyValue("position", position)
                .addKeyValue("uploadId", uploadId).addKeyValue("replaced", failed.id()).log("upload.image_replaced");
        return new Replaced(position, uploadId, expiresAt);
    }

    /**
     * Leaves out the failed image at {@code position} of the owner's set: the images after it move up by one place,
     * and the set starts when it is now whole; the last image left out discards the version. Answers the material, the
     * version and its state.
     */
    public Uploads.Completed drop(UUID setId, int position) {
        UUID owner = OwnerContext.require();
        Instant now = service.now();
        record Dropped(UploadRow row, VersionRow version) {
        }
        Dropped dropped = service.inTransaction(() -> {
            VersionRow version = lockedSet(setId);
            List<UploadRow> members = members(version);
            UploadRow at = failedAt(members, position);
            if (!uploads.joined().removeFailed(at.id(), now)) {
                throw UploadProblems.notFound();
            }
            uploads.joined().closeGap(owner, setId, position, now);
            if (members.size() == 1) {
                materials.changeVersion(setId, Uploads.DISCARDED, null, null, now);
                return new Dropped(at, materials.findVersion(setId).orElseThrow(UploadProblems::notFound));
            }
            return new Dropped(at, service.startIfReady(version, now));
        });
        finish(dropped.row());
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", setId).addKeyValue("position", position)
                .addKeyValue("state", dropped.version().state()).log("upload.image_dropped");
        return new Uploads.Completed(dropped.version().materialId(), setId, dropped.version().state());
    }

    // --- shared

    /** The image's name, checked like a material's, and its size: 1 byte to {@link Uploads#MAX_LENGTH}. */
    private static String checked(Image image) {
        if (image == null || image.length() < 1) {
            throw UploadProblems.invalid();
        }
        if (image.length() > Uploads.MAX_LENGTH) {
            throw UploadProblems.setTooLarge();
        }
        return Uploads.validName(image.name());
    }

    /** The owner's set, locked: a version still uploading. */
    private VersionRow lockedSet(UUID setId) {
        return materials.lockVersion(setId).filter(version -> Uploads.UPLOADING.equals(version.state()))
                .orElseThrow(UploadProblems::notFound);
    }

    private List<UploadRow> members(UUID setId) {
        return members(lockedSet(setId));
    }

    /** The set's members in order; a version with none is no set. */
    private List<UploadRow> members(VersionRow version) {
        List<UploadRow> members = uploads.joined().ofVersion(version.ownerId(), version.id()).stream()
                .filter(UploadRow::member).toList();
        if (members.isEmpty()) {
            throw UploadProblems.notFound();
        }
        return members;
    }

    private static UploadRow failedAt(List<UploadRow> members, int position) {
        Optional<UploadRow> at = members.stream().filter(member -> member.position() == position).findFirst();
        if (at.isEmpty()) {
            throw UploadProblems.notFound();
        }
        if (!UploadRow.FAILED.equals(at.get().state())) {
            throw UploadProblems.imageNotFailed();
        }
        return at.get();
    }

    private UploadRow member(UUID uploadId, UUID owner, UUID versionId, long length, int position, String name,
                             Instant expiresAt, Instant now) {
        return new UploadRow(uploadId, owner, versionId, length, 0, 0, null, expiresAt, null, null, UploadRow.OPEN, now,
                now, position, vault.encryptText(Uploads.memberName(uploadId, owner), name), null);
    }

    /** A failed member's pieces and row go after the commit; the upload sweep finishes whatever is left. */
    private void finish(UploadRow failed) {
        try {
            service.finishRemoval(failed);
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("uploadId", failed.id()).addKeyValue("errorType", failure.getClass().getName())
                    .log("upload.removal_deferred");
        }
    }
}
