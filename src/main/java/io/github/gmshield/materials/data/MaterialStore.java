package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code material} and {@code material_version}, always within the owner of the work: every read sees that owner's
 * rows only, and a write for another owner is refused. Writes join the caller's transaction, which must exist.
 */
public interface MaterialStore {

    void insertMaterial(MaterialRow row);

    void insertVersion(VersionRow row);

    /** The current owner's material with this id; empty for an unknown id or another owner's. */
    Optional<MaterialRow> findMaterial(UUID id);

    /** The current owner's version with this id; empty for an unknown id or another owner's. */
    Optional<VersionRow> findVersion(UUID id);

    /** Moves the current owner's version to {@code state}, with its format and size; false when there is none. */
    boolean changeVersion(UUID id, String state, String format, Long sizeBytes, Instant now);

    /** Deletes the current owner's version; false when there is none. */
    boolean deleteVersion(UUID id);

    /** Deletes the current owner's material, which must have no version left; false when there is none. */
    boolean deleteMaterial(UUID id);
}
