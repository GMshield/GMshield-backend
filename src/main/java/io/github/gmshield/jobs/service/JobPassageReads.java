package io.github.gmshield.jobs.service;

import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobStorage;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.vault.service.Vault;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Passage text by id for a claimed job (Story 2.2, internal contract 1.8.0), generic over every kind. The live claim's
 * grant comes first, exactly as for a renewal ({@code job.grant_refused}, 403); then 1 to {@value #MAX_IDS} ids, none
 * null ({@code job.passages_invalid}, 400). The job's handler answers ({@link JobHandler#passages}) as the job's owner,
 * in a read transaction of its own; a kind without one, or whose handler keeps the default, is
 * {@code job.passages_refused} (403). Lines carry ids, counts and codes only, never a text or the grant.
 */
@Service
public class JobPassageReads {

    /** The most ids one request may ask for. */
    public static final int MAX_IDS = 100;
    private static final Logger log = LoggerFactory.getLogger(JobPassageReads.class);

    private final JobStorage storage;
    private final JobHandlers handlers;
    private final OwnerWork owners;
    private final JobClock clock;
    private final Vault vault;

    JobPassageReads(JobStorage storage, JobHandlers handlers, OwnerWork owners, JobClock clock, Vault vault) {
        this.storage = storage;
        this.handlers = handlers;
        this.owners = owners;
        this.clock = clock;
        this.vault = vault;
    }

    /** The passages with these ids for the claimed job, in the order asked, each once. */
    public List<JobPassage> read(UUID jobId, UUID claimId, String grant, List<UUID> ids) {
        byte[] presented = JobClaims.hashOf(vault, grant);
        JobRow job = storage.joined().find(jobId).orElse(null);
        if (job == null || !JobClaims.grantMatches(job, claimId, presented, clock.now())) {
            JobClaims.refusedGrant(jobId);
            throw JobProblems.grantRefused();
        }
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch(Objects::isNull)) {
            JobTrace.run(job.traceId(), () -> log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                    .addKeyValue("kind", job.kind()).addKeyValue("requested", ids == null ? 0 : ids.size())
                    .addKeyValue("reason", JobProblems.PASSAGES_INVALID.code()).log("request.refused"));
            throw JobProblems.passagesInvalid();
        }
        List<UUID> asked = List.copyOf(ids);
        Optional<JobHandler> handler = handlers.find(job.kind());
        return JobTrace.call(job.traceId(), () -> {
            try {
                List<JobPassage> passages = owners.read(job.ownerId(), () -> handler
                        .orElseThrow(JobProblems::passagesRefused).passages(JobClaims.ref(job), asked));
                log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                        .addKeyValue("requested", asked.size()).addKeyValue("passages", passages.size())
                        .log("job.passages_read");
                return passages;
            } catch (ApiException refused) {
                log.atInfo().addKeyValue("jobId", jobId).addKeyValue("kind", job.kind())
                        .addKeyValue("requested", asked.size()).addKeyValue("reason", refused.problem().code())
                        .log("request.refused");
                throw refused;
            }
        });
    }
}
