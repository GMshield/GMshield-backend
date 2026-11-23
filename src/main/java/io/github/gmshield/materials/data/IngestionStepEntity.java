package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code ingestion_step} (Story 1.15): Hibernate fills {@code owner_id} from the owner of the work and filters every
 * query by it. Its version is a plain id: the database's key on (owner, version) keeps it within the same owner.
 * Written once; only {@code ended_at} changes, once (Story 1.16), a held step's {@code job_id}, once, when its job
 * is created (Story 1.18), and a records step's {@code records_unread} (Story 4.2).
 */
@Entity(name = "IngestionStep")
@Table(name = "ingestion_step")
class IngestionStepEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "stage", nullable = false, updatable = false)
    private String stage;

    @Column(name = "first_page", updatable = false)
    private Integer firstPage;

    @Column(name = "last_page", updatable = false)
    private Integer lastPage;

    @Column(name = "input_file_id", updatable = false)
    private UUID inputFileId;

    @Column(name = "input_texts", nullable = false, updatable = false)
    private boolean inputTexts;

    @Column(name = "input_passages", nullable = false, updatable = false)
    private boolean inputPassages;

    @Column(name = "records_unread", nullable = false)
    private boolean recordsUnread;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected IngestionStepEntity() {
        // for Hibernate
    }

    IngestionStepEntity(StepRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.jobId = row.jobId();
        this.stage = row.stage();
        this.firstPage = row.firstPage();
        this.lastPage = row.lastPage();
        this.inputFileId = row.inputFileId();
        this.inputTexts = row.inputTexts();
        this.inputPassages = row.inputPassages();
        this.recordsUnread = row.recordsUnread();
        this.createdAt = row.createdAt();
        this.endedAt = row.endedAt();
    }

    /** The step ended at {@code now}; a step that ended already keeps its time. */
    void end(Instant now) {
        if (endedAt == null) {
            endedAt = now;
        }
    }

    /** A held step gets its job; a step that has one keeps it. Answers whether it was given this one. */
    boolean start(UUID job) {
        if (jobId != null || endedAt != null) {
            return false;
        }
        jobId = job;
        return true;
    }

    /** A records step's pages are unread, or read again (Story 4.2); any other step keeps false. */
    void unread(boolean unread) {
        if (inputPassages) {
            recordsUnread = unread;
        }
    }

    StepRow row() {
        return new StepRow(id, ownerId, versionId, jobId, stage, firstPage, lastPage, inputFileId, inputTexts,
                inputPassages, recordsUnread, createdAt, endedAt);
    }
}
