package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.materials.data.IndexStore;
import io.github.gmshield.materials.data.RecordRemovalRow;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The removal of a deleted record's point from the index (Story 4.5 part B, internal contract 1.22.0): the job kind
 * {@value #KIND} on the ingestion lane, modelled on {@link IndexRemovals}. {@link #start} records one, in the caller's
 * transaction (the GM's delete, through {@link RecordIndexing#removePoint}), as a {@code record_removal} row keyed by the
 * job. At the claim the worker gets {@code owner}, {@code source} (the record's version) and {@code ids} (the record's
 * id), and deletes those points by id within that owner's source; a repeat deletes nothing more. A new kind rather than
 * ids on {@code index.removal}, because a worker that ignored them would delete the whole source.
 *
 * <p>Its completion records that the point is gone ({@link ExtractedRecords#pointsRemoved}): until then an answer whose
 * search still finds the point may read the record, and from then on nothing does. A failed attempt is offered again by
 * the jobs module, and a job that failed after its attempts gets a new one from the {@link IndexRemovalRetrySweep}
 * ({@link #retryFailed}), its row moving to it; the record stays hidden from every public read meanwhile. Lines carry
 * ids and codes only.
 */
@Service
public class RecordRemovals implements JobHandler {

    public static final String KIND = "index.record_removal";
    private static final Logger log = LoggerFactory.getLogger(RecordRemovals.class);

    private final Jobs jobs;
    private final IndexStore index;
    private final ExtractedRecords records;

    RecordRemovals(Jobs jobs, IndexStore index, ExtractedRecords records) {
        this.jobs = jobs;
        this.index = index;
        this.records = records;
    }

    /** Creates the removal of the point of the owner's record in its version, in the caller's transaction; its job. */
    UUID start(UUID versionId, UUID recordId) {
        UUID owner = OwnerContext.require();
        UUID jobId = jobs.create(KIND, job -> index.insertRecordRemoval(new RecordRemovalRow(job.id(), owner,
                versionId, recordId))).id();
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", versionId).addKeyValue("recordId", recordId)
                .addKeyValue("jobId", jobId).log("index.record_removal_created");
        return jobId;
    }

    /**
     * For the {@link IndexRemovalRetrySweep}, in the caller's transaction, as the bound owner: each failed {@value #KIND}
     * job of the owner that still has its row gets a new job, and the row moves to it. Answers how many got one.
     */
    int retryFailed() {
        UUID owner = OwnerContext.require();
        Set<UUID> failed = jobs.failedOfKind(KIND);
        List<RecordRemovalRow> rows = failed.isEmpty() ? List.of() : index.recordRemovals(failed);
        int retried = 0;
        for (RecordRemovalRow row : rows) {
            JobRef job = jobs.create(KIND, created -> index.moveRecordRemoval(row.jobId(), created.id())
                    .orElseThrow(() -> new IllegalStateException("index.record_removal_missing")));
            retried++;
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", row.versionId())
                    .addKeyValue("recordId", row.recordId()).addKeyValue("failedJobId", row.jobId())
                    .addKeyValue("jobId", job.id()).log("index.record_removal_retried");
        }
        return retried;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Lane lane() {
        return Lane.INGESTION;
    }

    @Override
    public JsonNode input(JobRef job) {
        RecordRemovalRow row = row(job);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("owner", row.ownerId().toString());
        input.put("source", row.versionId().toString());
        input.putArray("ids").add(row.recordId().toString());
        return input;
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        RecordRemovalRow row = row(job);
        int marked = records.pointsRemoved(List.of(row.recordId()));
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("versionId", row.versionId())
                .addKeyValue("recordId", row.recordId()).addKeyValue("marked", marked)
                .log("index.record_removal_done");
    }

    @Override
    public void failed(JobRef job, String code) {
        log.atWarn().addKeyValue("jobId", job.id()).addKeyValue("code", code).log("index.record_removal_failed");
    }

    private RecordRemovalRow row(JobRef job) {
        return index.findRecordRemoval(job.id())
                .orElseThrow(() -> new IllegalStateException("index.record_removal_missing"));
    }
}
