package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactIndexRemovalRow;
import io.github.gmshield.artifacts.data.ArtifactIndexStore;
import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
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
 * The removal of an artifact's points from the index (Story 5.1 part B): the job kind {@value #KIND} on the ingestion
 * lane, which the worker runs with its {@code index.removal} logic. A kind of its own, since the materials' handler of
 * {@code index.removal} keys on a material version. {@link #start} records one, in the caller's transaction (a
 * campaign's or a system's deletion), as an {@code artifact_index_removal} row keyed by the job, which names no row of
 * the artifact. At the claim the worker gets {@code source} (the artifact's id) and {@code owner}, and deletes the points
 * by a filter on both, so a removal is safe to repeat. Nothing is recorded when it completes. A removal whose job failed
 * gets a new job with the same input ({@link #retryFailed}, from {@link ArtifactIndexRetrySweep}): the row moves to it.
 */
@Service
public class ArtifactIndexRemovals implements JobHandler {

    public static final String KIND = "index.artifact_removal";
    private static final Logger log = LoggerFactory.getLogger(ArtifactIndexRemovals.class);

    private final Jobs jobs;
    private final ArtifactIndexStore index;

    ArtifactIndexRemovals(Jobs jobs, ArtifactIndexStore index) {
        this.jobs = jobs;
        this.index = index;
    }

    /** Creates the removal of the owner's artifact's points, in the caller's transaction; answers its job's id. */
    UUID start(UUID artifactId) {
        UUID owner = OwnerContext.require();
        UUID jobId = jobs.create(KIND, job -> index.insertRemoval(new ArtifactIndexRemovalRow(job.id(), owner,
                artifactId))).id();
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("artifactId", artifactId).addKeyValue("jobId", jobId)
                .log("artifacts.index_removal_created");
        return jobId;
    }

    /**
     * Each failed removal job of the owner that still has its row gets a new job, in the caller's transaction; answers
     * how many.
     */
    int retryFailed() {
        UUID owner = OwnerContext.require();
        Set<UUID> failed = jobs.failedOfKind(KIND);
        List<ArtifactIndexRemovalRow> rows = failed.isEmpty() ? List.of() : index.removals(failed);
        int retried = 0;
        for (ArtifactIndexRemovalRow row : rows) {
            JobRef job = jobs.create(KIND, created -> index.moveRemoval(row.jobId(), created.id())
                    .orElseThrow(() -> new IllegalStateException("artifacts.index_removal_missing")));
            retried++;
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("artifactId", row.artifactId())
                    .addKeyValue("failedJobId", row.jobId()).addKeyValue("jobId", job.id())
                    .log("artifacts.index_removal_retried");
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
        ArtifactIndexRemovalRow row = index.findRemoval(job.id())
                .orElseThrow(() -> new IllegalStateException("artifacts.index_removal_missing"));
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("source", row.artifactId().toString());
        input.put("owner", row.ownerId().toString());
        return input;
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        log.atInfo().addKeyValue("jobId", job.id()).log("artifacts.index_removal_done");
    }

    @Override
    public void failed(JobRef job, String code) {
        log.atWarn().addKeyValue("jobId", job.id()).addKeyValue("code", code).log("artifacts.index_removal_failed");
    }
}
