package io.github.gmshield.jobs.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Passage text by id for a claimed job (Story 2.2, internal contract 1.8.0), generic over every kind. The live claim's
 * grant comes first, exactly as for a renewal ({@code job.grant_refused}, 403); then 1 to {@value #MAX_IDS} ids, none
 * null ({@code job.passages_invalid}, 400). The job's handler answers ({@link JobHandler#passages}) as the job's owner,
 * in a read transaction of its own; a kind without one, or whose handler keeps the default, is
 * {@code job.passages_refused} (403). Lines carry ids, counts and codes only, never a text or the grant
 * ({@link JobClaimReads}, shared with {@link JobRecordReads}).
 */
@Service
public class JobPassageReads {

    /** The most ids one request may ask for. */
    public static final int MAX_IDS = JobClaimReads.MAX_IDS;
    private static final JobClaimReads.Kind<JobPassage> PASSAGES = new JobClaimReads.Kind<>(
            JobProblems::passagesInvalid, JobProblems::passagesRefused, JobHandler::passages, "job.passages_read",
            "passages");

    private final JobClaimReads reads;

    JobPassageReads(JobClaimReads reads) {
        this.reads = reads;
    }

    /** The passages with these ids for the claimed job, in the order asked, each once. */
    public List<JobPassage> read(UUID jobId, UUID claimId, String grant, List<UUID> ids) {
        return reads.read(jobId, claimId, grant, ids, PASSAGES);
    }
}
