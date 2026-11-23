package io.github.gmshield.jobs.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Records by id for a claimed job (Story 4.3 part C, internal contract 1.19.0), generic over every kind, as
 * {@link JobPassageReads} reads passages: the live claim's grant first ({@code job.grant_refused}, 403); then 1 to
 * {@value #MAX_IDS} ids, none null ({@code job.records_invalid}, 400). The job's handler answers
 * ({@link JobHandler#records}) as the job's owner, in a read transaction of its own; a kind without one, or whose
 * handler keeps the default, is {@code job.records_refused} (403). Lines carry ids, counts and codes only, never a
 * name, a field or the grant.
 */
@Service
public class JobRecordReads {

    /** The most ids one request may ask for. */
    public static final int MAX_IDS = JobClaimReads.MAX_IDS;
    private static final JobClaimReads.Kind<JobRecord> RECORDS = new JobClaimReads.Kind<>(
            JobProblems::recordsInvalid, JobProblems::recordsRefused, JobHandler::records, "job.records_read",
            "records");

    private final JobClaimReads reads;

    JobRecordReads(JobClaimReads reads) {
        this.reads = reads;
    }

    /** The records with these ids for the claimed job, in the order asked, each once. */
    public List<JobRecord> read(UUID jobId, UUID claimId, String grant, List<UUID> ids) {
        return reads.read(jobId, claimId, grant, ids, RECORDS);
    }
}
