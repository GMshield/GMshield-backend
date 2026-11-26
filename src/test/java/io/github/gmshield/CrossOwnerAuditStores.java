package io.github.gmshield;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What {@link CrossOwnerAuditFlowTest} reads and does beside its HTTP requests: the stores' rows it checks and the job
 * lane it frees. The unit audit reads the in-memory twins ({@link InMemoryCrossOwnerAuditStores}); the integration
 * audit ({@code CrossOwnerAuditIntegrationTest}) reads PostgreSQL and Redis. {@code owners} are the audit's two
 * accounts: a store shared with other tests is read by them only.
 */
interface CrossOwnerAuditStores {

    /** An ingestion step: its job, its stage and its input file. */
    record Step(UUID jobId, String stage, UUID inputFileId) {
    }

    /** A job as the body-field sweep checks it. */
    record Job(UUID id, UUID ownerId, String kind) {
    }

    /** A search as the body-field sweep checks it. */
    record Search(UUID id, UUID ownerId, UUID campaignId) {
    }

    /**
     * Before each test: whatever an earlier test left unended no longer holds the ingestion lane. The in-memory
     * implementation also resets its clock, ends the open jobs of every lane and clears the streams (its context holds
     * this class's rows only); the PostgreSQL one ends the ingestion lane's open jobs only (the database and Redis are
     * shared with the other integration classes, and its clock is real).
     */
    void start();

    /** Ends the ingestion lane's open jobs (an accepted version's index changes), so the next ingestion runs. */
    void endIngestion();

    /** The account that signed in with the stand-in's {@code sub}, found by its keyed Google id fingerprint. */
    UUID account(String sub);

    /** The version's state. */
    String versionState(UUID versionId);

    /** One passage of the version. */
    UUID passage(UUID versionId);

    /** The turn's job. */
    UUID turnJob(UUID turnId);

    /** The ids of the owner's jobs. */
    Set<UUID> jobIds(UUID ownerId);

    /** The version's steps whose job is offered. */
    List<Step> offeredSteps(UUID versionId);

    /**
     * A copy of the stores' rows, by table and key, for {@link CrossOwnerAuditFlowTest#differences}: the rows of
     * {@code owners} at least. The job table is named {@code job} and the search table {@code search}, each keyed by
     * its id. In memory the tables are a hand-kept list; on PostgreSQL they are every table with an {@code owner_id}
     * column. Story 5.1: an artifact is a row of {@code record} on PostgreSQL, kept apart in memory as {@code artifact};
     * its versions are {@code record_version} ({@code record version} in memory).
     */
    Map<String, Map<Object, Object>> tables(Collection<UUID> owners);

    /**
     * Which tables of {@link #tables} hold which of A's items, by the audit's names for them (from Story 5.1 the
     * artifact too).
     */
    Map<String, List<String>> held();

    /** The job ids offered on each work stream. */
    Map<String, List<UUID>> offered();

    /** The jobs of {@code owners} at least. */
    List<Job> jobs(Collection<UUID> owners);

    /** The searches of {@code owners} at least. */
    List<Search> searches(Collection<UUID> owners);
}
