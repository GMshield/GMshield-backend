package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code material} and {@code material_version}, always within the owner of the work: every read sees that owner's
 * rows only, and a write for another owner is refused. Writes join the caller's transaction, which must exist.
 */
public interface MaterialStore {

    void insertMaterial(MaterialRow row);

    void insertVersion(VersionRow row);

    /** Says that a material belongs to a game system (Story 1.14). */
    void insertMaterialSystem(MaterialSystemRow row);

    /** The current owner's game systems of a material, oldest first. */
    List<MaterialSystemRow> systemsOf(UUID materialId);

    /**
     * The current owner's materials whose newest version (the latest created, then the greatest id) is in one of
     * {@code states}, each with that version: newest material first (descending id, a UUID v7), only those whose id is
     * below {@code before} when it is given, at most {@code limit}.
     */
    List<NewestVersion> newestIn(Collection<String> states, UUID before, int limit);

    /** The current owner's material with this id and its newest version; empty for an unknown id or another owner's. */
    Optional<NewestVersion> newest(UUID materialId);

    /** The current owner's material with this id; empty for an unknown id or another owner's. */
    Optional<MaterialRow> findMaterial(UUID id);

    /** The current owner's version with this id; empty for an unknown id or another owner's. */
    Optional<VersionRow> findVersion(UUID id);

    /** Moves the current owner's version to {@code state}, with its format and size; false when there is none. */
    boolean changeVersion(UUID id, String state, String format, Long sizeBytes, Instant now);

    /** Deletes the current owner's version; false when there is none. */
    boolean deleteVersion(UUID id);

    /**
     * Deletes the current owner's material, which must have no version left, with the game systems it belongs to;
     * false when there is none.
     */
    boolean deleteMaterial(UUID id);
}
