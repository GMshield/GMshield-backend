package io.github.gmshield.jobs.service;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The terms of a claimed job's question (Story 4.6, internal contract 1.23.0), generic over every kind: the live
 * claim's grant first ({@code job.grant_refused}, 403); then the job's handler answers ({@link JobHandler#terms}) as the
 * job's owner, in a read transaction of its own; a kind without one, or whose handler keeps the default, is
 * {@code job.terms_refused} (403). The request has no body: the handler reads the question its job holds. Lines carry
 * ids, counts and codes only, never a name or the grant.
 */
@Service
public class JobTermReads {

    private final JobClaimReads reads;

    JobTermReads(JobClaimReads reads) {
        this.reads = reads;
    }

    /** The terms of the claimed job's question. */
    public JobTerms read(UUID jobId, UUID claimId, String grant) {
        return reads.terms(jobId, claimId, grant);
    }
}
