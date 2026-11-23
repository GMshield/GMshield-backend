package io.github.gmshield.materials.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * The records a version's ingestion extracts (Story 4.2), as this module hands them over: the {@code knowledge} module
 * implements it, because {@code knowledge} depends on {@code materials} and never the reverse. Every call acts for the
 * owner of the work ({@code OwnerContext}) and joins the caller's transaction, which must exist; another owner's
 * version has no records. A record's name and fields are encrypted where they are stored.
 *
 * <p>Whatever replaces or removes a version's passages removes its records first ({@link #removeVersion}), in the same
 * transaction: a record links to the passages it was read from.
 *
 * <p>Story 4.3 part B: a campaign's search reads records through {@link #located}, {@link #named} and {@link #names},
 * for the {@code scope} module, which checks them against the campaign's scope. Whether a version is {@code ready} and
 * allowed is the caller's check, never this port's.
 */
@NamedInterface("service")
public interface ExtractedRecords {

    /** A record's field: a name and a value, both non-blank. {@code toString} names neither. */
    @NamedInterface("service")
    record Field(String name, String value) {
        @Override
        public String toString() {
            return "Field";
        }
    }

    /**
     * One extracted record: its core type's key ({@code rule}, {@code spell} or {@code item}), its name, its fields in
     * order, the ids of the passages it was read from (at least one, each a passage of the version), and the lowest page
     * of those passages. {@code toString} names no content.
     */
    @NamedInterface("service")
    record Extracted(String type, String name, List<Field> fields, List<UUID> passageIds, int firstPage) {
        @Override
        public String toString() {
            return "Extracted[type=" + type + ", fields=" + fields.size() + ", passages=" + passageIds.size()
                    + ", firstPage=" + firstPage + "]";
        }
    }

    /**
     * One stored record as the index reads it (Story 4.2 part B): its id, its core type's key, its first page, its name
     * and its fields in order, decrypted. {@code toString} names no content.
     */
    @NamedInterface("service")
    record Stored(UUID id, String type, int firstPage, String name, List<Field> fields) {
        @Override
        public String toString() {
            return "Stored[id=" + id + ", type=" + type + ", firstPage=" + firstPage + ", fields=" + fields.size() + "]";
        }
    }

    /**
     * The version's records whose first page is within {@code firstPage} to {@code lastPage} become {@code records}, in
     * that order (a records step's report, Story 4.2: the same step reported again replaces its records, never adds to
     * them). Answers how many records went.
     */
    int replaceRange(UUID versionId, int firstPage, int lastPage, List<Extracted> records);

    /** Removes every record of the version, with its links to passages; safe to repeat. Answers how many went. */
    int removeVersion(UUID versionId);

    /** How many records each of these versions' records has by core type key; a type with none is left out. */
    Map<String, Long> countsByType(Collection<UUID> versionIds);

    /**
     * The first page of each of the version's records, one entry per record, by first page then ordinal (how the index
     * cuts its records runs, Story 4.2 part B); empty for a version with none.
     */
    List<Integer> firstPages(UUID versionId);

    /**
     * The version's records whose first page is within {@code firstPage} to {@code lastPage}, decrypted, in the order of
     * {@link #firstPages} (one records run's input, Story 4.2 part B). Only the index job's input reads them.
     */
    List<Stored> readRange(UUID versionId, int firstPage, int lastPage);

    /**
     * Where a stored record stands (Story 4.3 part B): its id, its version, its core type's key, its first page and the
     * ids of every passage it links to, in order. {@code toString} names ids and numbers only.
     */
    @NamedInterface("service")
    record Located(UUID id, UUID versionId, String type, int firstPage, List<UUID> passageIds) {
        public Located {
            passageIds = List.copyOf(passageIds);
        }

        @Override
        public String toString() {
            return "Located[id=" + id + ", versionId=" + versionId + ", type=" + type + ", firstPage=" + firstPage
                    + ", passages=" + passageIds.size() + "]";
        }
    }

    /**
     * The records with these ids, in the order given, each once; an id that is not one of the owner's records is left
     * out. Nothing is decrypted.
     */
    List<Located> located(Collection<UUID> ids);

    /**
     * The records of these versions whose name equals {@code name} once both are in their unique form (trimmed, NFC,
     * lower case in the root locale: {@code library.service.Library#uniqueForm}), found by an equality on the name's
     * keyed fingerprint, never by a text search; by id. A record stored before V37 has no fingerprint and never
     * matches. Nothing is decrypted.
     */
    List<Located> named(Collection<UUID> versionIds, String name);

    /** The decrypted names of the owner's records with these ids; an unknown id or another owner's is left out. */
    Map<UUID, String> names(Collection<UUID> ids);
}
