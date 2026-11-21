package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.materials.data.IndexRemovalRow;
import io.github.gmshield.materials.data.IndexStore;
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
 * The removal of a version's points from the index (Story 2.1, AD-10): the job kind {@value #KIND} on the ingestion
 * lane. {@link #start} records one, in the caller's transaction (a discard; Story 3.4's delete will reuse it), as an
 * {@code index_removal} row keyed by the job. At the claim the worker gets {@code source} (the version's id) and
 * {@code owner}, and deletes the points by a filter on both, never by a list of ids: a removal is safe to repeat, and
 * it removes whatever an index change wrote before it was cancelled. Nothing is recorded when it completes.
 *
 * <p>Story 3.4 part B: a removal whose job failed after its attempts would leave points nothing tracks. The
 * {@link IndexRemovalRetrySweep} calls {@link #retryFailed} for each owner with a failed job of this kind: each failed
 * job that still has its {@code index_removal} row gets a new job with the same input (owner and source), and the row
 * moves to it; the failed job stays as history and, having no row, is never retried twice. The version row is never
 * needed: the removal outlives it.
 */
@Service
public class IndexRemovals implements JobHandler {

    public static final String KIND = "index.removal";
    private static final Logger log = LoggerFactory.getLogger(IndexRemovals.class);

    private final Jobs jobs;
    private final IndexStore index;

    IndexRemovals(Jobs jobs, IndexStore index) {
        this.jobs = jobs;
        this.index = index;
    }

    /** Creates the removal of the owner's version's points, in the caller's transaction; answers its job's id. */
    UUID start(UUID versionId) {
        UUID owner = OwnerContext.require();
        UUID jobId = jobs.create(KIND, job -> index.insertRemoval(new IndexRemovalRow(job.id(), owner, versionId))).id();
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", versionId).addKeyValue("jobId", jobId)
                .log("index.removal_created");
        return jobId;
    }

    /**
     * For the {@link IndexRemovalRetrySweep}, in the caller's transaction, as the bound owner: each failed
     * {@code index.removal} job of the owner that still has its row gets a new job, and the row moves to it. Answers
     * how many got one.
     */
    int retryFailed() {
        UUID owner = OwnerContext.require();
        Set<UUID> failed = jobs.failedOfKind(KIND);
        List<IndexRemovalRow> rows = failed.isEmpty() ? List.of() : index.removals(failed);
        int retried = 0;
        for (IndexRemovalRow row : rows) {
            JobRef job = jobs.create(KIND, created -> index.moveRemoval(row.jobId(), created.id())
                    .orElseThrow(() -> new IllegalStateException("index.removal_missing")));
            retried++;
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("versionId", row.versionId())
                    .addKeyValue("failedJobId", row.jobId()).addKeyValue("jobId", job.id())
                    .log("index.removal_retried");
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
        IndexRemovalRow row = index.findRemoval(job.id())
                .orElseThrow(() -> new IllegalStateException("index.removal_missing"));
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("source", row.versionId().toString());
        input.put("owner", row.ownerId().toString());
        return input;
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        log.atInfo().addKeyValue("jobId", job.id()).log("index.removal_done");
    }

    @Override
    public void failed(JobRef job, String code) {
        log.atWarn().addKeyValue("jobId", job.id()).addKeyValue("code", code).log("index.removal_failed");
    }
}
