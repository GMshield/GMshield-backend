package io.github.gmshield.knowledge.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code record} and {@code record_passage} (Story 4.2), always within the owner of the work: every read sees that
 * owner's rows only, and an insert for another owner is refused. Writes join the caller's transaction, which must exist.
 * The store checks no passage or version beyond the database's keys: the ingestion chain checks them first.
 */
public interface RecordStore {

    /** Stores new records of the current owner, each with its links to passages. */
    void insert(List<RecordRow> rows);

    /** The current owner's records of a version, by first page then ordinal, each with its passage ids in order. */
    List<RecordRow> ofVersion(UUID versionId);

    /**
     * Deletes the current owner's records of a version whose first page is within {@code firstPage} to
     * {@code lastPage}, their links first; answers how many records went.
     */
    int deleteRange(UUID versionId, int firstPage, int lastPage);

    /** Deletes every record of the current owner's version, their links first; answers how many records went. */
    int deleteOfVersion(UUID versionId);

    /** How many records of the current owner's versions with these ids each type has; a type with none is left out. */
    Map<String, Long> countsByType(Collection<UUID> versionIds);
}
