package io.github.gmshield.knowledge.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code record} (Story 4.2, V35): Hibernate fills {@code owner_id} from the owner of the work and filters every query
 * by it. The version is a plain id: the database's key on (owner, version) keeps it within the same owner. A step
 * reported again replaces its records with new rows. From Story 4.5 (V41) a correction rewrites its type, name, name
 * fingerprint and fields in place; its id, version id, ordinal, first page and links never change. Its {@code version}
 * is Hibernate's: each correction raises it, and an update of a row whose version moved meanwhile fails. From Story
 * 4.5 part B (V42) the GM's delete marks it ({@code deleted_at}) and the removal of its point marks it again
 * ({@code point_removed_at}); neither ever goes back to null.
 *
 * <p>Story 5.1 (V43): an artifact is a row of this table too, the identity and type key of a record the GM wrote in a
 * campaign ({@code campaign_id}). It has no version, ordinal, first page, name or fields (its content is in the
 * {@code artifacts} module's {@code record_version}), so those are boxed and null for it, and exactly one of the version
 * and the campaign is set. Every read of an extracted record leaves an artifact out (it filters through a version), and
 * an artifact is read only by the artifact reads, which name its campaign.
 */
@Entity(name = "Record")
@Table(name = "record")
class RecordEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    /** The material version of an extracted record; null for an artifact (Story 5.1). */
    @Column(name = "version_id", updatable = false)
    private UUID versionId;

    /** The campaign of an artifact (Story 5.1, V43); null for an extracted record. */
    @Column(name = "campaign_id", updatable = false)
    private UUID campaignId;

    @Column(name = "ordinal", updatable = false)
    private Integer ordinal;

    @Column(name = "first_page", updatable = false)
    private Integer firstPage;

    /** The type's key: a JPQL path names it {@code recordType}, never the keyword {@code type}. */
    @Column(name = "type", nullable = false)
    private String recordType;

    @Column(name = "name")
    private byte[] name;

    /** The name's keyed fingerprint (V37); null for a record stored before it and never corrected. */
    @Column(name = "name_fingerprint")
    private byte[] nameFingerprint;

    @Column(name = "fields")
    private byte[] fields;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long rowVersion;

    @Column(name = "corrected_at")
    private Instant correctedAt;

    @Column(name = "extracted_type")
    private String extractedType;

    @Column(name = "extracted_name_fingerprint")
    private byte[] extractedNameFingerprint;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "point_removed_at")
    private Instant pointRemovedAt;

    protected RecordEntity() {
        // for Hibernate
    }

    RecordEntity(RecordRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.ordinal = row.ordinal();
        this.firstPage = row.firstPage();
        this.recordType = row.type();
        this.name = row.name();
        this.nameFingerprint = row.nameFingerprint();
        this.fields = row.fields();
        this.createdAt = row.createdAt();
        this.rowVersion = row.version();
        this.correctedAt = row.correctedAt();
        this.extractedType = row.extractedType();
        this.extractedNameFingerprint = row.extractedNameFingerprint();
        this.deletedAt = row.deletedAt();
        this.pointRemovedAt = row.pointRemovedAt();
    }

    /** An artifact's identity row (Story 5.1): its campaign and type key, with no version, place, name or fields. */
    RecordEntity(ArtifactRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.campaignId = row.campaignId();
        this.recordType = row.type();
        this.createdAt = row.createdAt();
    }

    UUID id() {
        return id;
    }

    long rowVersion() {
        return rowVersion;
    }

    boolean deleted() {
        return deletedAt != null;
    }

    /**
     * The GM's delete (Story 4.5 part B): the row becomes a tombstone at {@code at}; with {@code pointRemoved} (a record
     * that was never indexed) its point counts as removed at once. Hibernate raises the version when it writes it.
     */
    void delete(Instant at, boolean pointRemoved) {
        this.deletedAt = at;
        if (pointRemoved) {
            this.pointRemovedAt = at;
        }
    }

    /** The removal of its point finished at {@code at} (Story 4.5 part B); the first time only. */
    void pointRemoved(Instant at) {
        if (pointRemovedAt == null) {
            this.pointRemovedAt = at;
        }
    }

    /**
     * The GM's correction (Story 4.5): on the first one, the type and fingerprint the extraction produced are kept as
     * the extracted match key; then the corrected values replace them. Hibernate raises the version when it writes them.
     */
    void correct(RecordCorrection correction) {
        if (correctedAt == null) {
            this.extractedType = recordType;
            this.extractedNameFingerprint = nameFingerprint == null ? null : nameFingerprint.clone();
        }
        write(correction);
    }

    /** A correction carried over from an earlier version's record (Story 4.5), with that record's extracted key. */
    void carry(RecordCorrection correction, String keptType, byte[] keptFingerprint) {
        this.extractedType = keptType;
        this.extractedNameFingerprint = keptFingerprint == null ? null : keptFingerprint.clone();
        write(correction);
    }

    private void write(RecordCorrection correction) {
        this.recordType = correction.type();
        this.name = correction.name();
        this.nameFingerprint = correction.nameFingerprint();
        this.fields = correction.fields();
        this.correctedAt = correction.correctedAt();
    }

    /** An artifact's type key, as its newest version's (Story 5.2); only an artifact's retype calls it. */
    void retype(String type) {
        if (campaignId == null) {
            throw new IllegalStateException("knowledge.not_an_artifact");
        }
        this.recordType = type;
    }

    /**
     * The GM's delete of an artifact (Story 5.2 part B): the row is deleting from {@code at} until its deletion's pass
     * removes it; no point time is set. Hibernate raises the version when it writes it.
     */
    void markDeleting(Instant at) {
        if (campaignId == null) {
            throw new IllegalStateException("knowledge.not_an_artifact");
        }
        this.deletedAt = at;
    }

    /** The row of an artifact (Story 5.1); only an artifact read calls it, and only on a row with a campaign. */
    ArtifactRow artifact() {
        if (campaignId == null) {
            throw new IllegalStateException("knowledge.not_an_artifact");
        }
        return new ArtifactRow(id, ownerId, campaignId, recordType, createdAt);
    }

    /** The row of an extracted record; only a read that filters through a version calls it. */
    RecordRow row(List<UUID> passageIds) {
        if (versionId == null || ordinal == null || firstPage == null) {
            throw new IllegalStateException("knowledge.not_an_extracted_record");
        }
        return new RecordRow(id, ownerId, versionId, ordinal, firstPage, recordType, name, nameFingerprint, fields,
                passageIds, createdAt, rowVersion, correctedAt, extractedType, extractedNameFingerprint, deletedAt,
                pointRemovedAt);
    }
}
