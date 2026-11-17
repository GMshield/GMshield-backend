package io.github.gmshield.jobs.service;

import io.github.gmshield.jobs.data.JobOutputRow;
import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobStorage;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Sha256;
import io.github.gmshield.vault.service.Vault;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The files of a claimed job (Story 1.15, internal contract 1.2.0), generic over every kind:
 *
 * <ul>
 *   <li>{@link #open}: an input the job's handler opens ({@link JobHandler#openInput}), streamed to the worker.</li>
 *   <li>{@link #store}: a file the worker sends back, streamed into the file store in 4 MiB pieces, each sealed
 *       by the vault, under an id the backend mints and the job's owner, with its SHA-256 computed as it arrives.
 *       It is a {@code job_output} until the handler adopts it ({@link Jobs#adoptOutput}); the jobs sweep removes
 *       every output still there once its job ended.</li>
 * </ul>
 *
 * Both need the live claim's grant, exactly as a renewal does ({@code job.grant_refused}, 403). An output is capped
 * at {@link JobFileSettings#outputMaxBytes()}, and a claim's stored outputs together at
 * {@link JobFileSettings#claimMaxBytes()} ({@code job.output_too_large}, 413): a refused or cut output leaves nothing.
 * The claim is checked again before every piece and, with the job's row locked, when the output is recorded whole,
 * so an output never outlives the claim that sent it. Nothing here logs a file's content, its name or the grant.
 */
@Service
public class JobFiles {

    /** The size of a stored piece, before encryption: the same as an upload's. */
    static final int PIECE_BYTES = 4 * 1024 * 1024;
    /** An input's name: a short lower-case word. */
    static final Pattern INPUT_NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Logger log = LoggerFactory.getLogger(JobFiles.class);

    private final JobStorage storage;
    private final JobHandlers handlers;
    private final OwnerWork owners;
    private final JobFileSettings settings;
    private final JobClock clock;
    private final Vault vault;
    private final FileStore files;

    JobFiles(JobStorage storage, JobHandlers handlers, OwnerWork owners, JobFileSettings settings, JobClock clock,
             Vault vault, FileStore files) {
        this.storage = storage;
        this.handlers = handlers;
        this.owners = owners;
        this.settings = settings;
        this.clock = clock;
        this.vault = vault;
        this.files = files;
    }

    /** An opened input and the trace id of its job, under which the caller logs the transfer. */
    public record Opened(JobInput input, String traceId) {
    }

    /** A stored output as the worker receives it: its id, its size and its SHA-256 in lower-case hexadecimal. */
    public record Stored(UUID fileId, long size, String sha256) {
    }

    /** The input {@code name} of the claimed job; {@code request.not_found} (404) when the handler has none. */
    public Opened open(UUID jobId, UUID claimId, String grant, String name) {
        JobRow job = live(jobId, claimId, grant);
        if (name == null || !INPUT_NAME.matcher(name).matches()) {
            throw JobProblems.notFound();
        }
        Optional<JobHandler> handler = handlers.find(job.kind());
        if (handler.isEmpty()) {
            throw JobProblems.notFound();
        }
        Optional<JobInput> input = JobTrace.call(job.traceId(), () -> owners.call(job.ownerId(),
                () -> handler.get().openInput(JobClaims.ref(job), name)));
        JobInput opened = input.orElseThrow(JobProblems::notFound);
        JobTrace.run(job.traceId(), () -> log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                .addKeyValue("size", opened.size()).log("job.input_opened"));
        return new Opened(opened, job.traceId());
    }

    /**
     * Streams {@code body} into a new output of the claimed job. {@code declaredLength} is the request's
     * {@code Content-Length} (-1 when it named none): a length over the limit is refused before anything is stored.
     */
    public Stored store(UUID jobId, UUID claimId, String grant, long declaredLength, InputStream body) {
        JobRow job = live(jobId, claimId, grant);
        long limit = Math.min(settings.outputMaxBytes(),
                settings.claimMaxBytes() - storage.joined().claimOutputBytes(jobId, claimId));
        if (declaredLength > limit || limit < 1) {
            throw JobProblems.outputTooLarge(settings.outputMaxBytes());
        }
        Instant started = clock.now();
        UUID fileId = Ids.newId();
        storage.separately(store -> {
            store.insertOutput(new JobOutputRow(fileId, jobId, job.ownerId(), claimId, JobOutputRow.WRITING, 0, 0,
                    started, started));
            return null;
        });
        byte[] presented = JobClaims.hashOf(vault, grant);
        try {
            Stored stored = JobTrace.call(job.traceId(), () -> write(job, claimId, presented, fileId, limit, body));
            JobTrace.run(job.traceId(), () -> log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                    .addKeyValue("fileId", fileId).addKeyValue("size", stored.size()).log("job.output_stored"));
            return stored;
        } catch (RuntimeException failure) {
            discard(job, fileId);
            throw failure;
        }
    }

    private Stored write(JobRow job, UUID claimId, byte[] presented, UUID fileId, long limit, InputStream body) {
        Sha256 digest = vault.sha256();
        byte[] buffer = new byte[PIECE_BYTES];
        long total = 0;
        int pieces = 0;
        while (true) {
            int filled = read(body, buffer);
            if (filled == 0) {
                break;
            }
            total += filled;
            if (total > limit) {
                throw JobProblems.outputTooLarge(settings.outputMaxBytes());
            }
            stillLive(job.id(), claimId, presented);
            digest.update(buffer, 0, filled);
            files.writePiece(job.ownerId(), fileId, pieces, filled == buffer.length ? buffer : Arrays.copyOf(buffer, filled));
            pieces++;
        }
        if (total == 0) {
            throw JobProblems.invalid();
        }
        long size = total;
        int count = pieces;
        boolean recorded = storage.separately(store -> {
            JobRow current = store.findForUpdate(job.id()).orElse(null);
            if (!JobClaims.grantMatches(current, claimId, presented, clock.now())) {
                return false;
            }
            return store.finishOutput(fileId, size, count, clock.now());
        });
        if (!recorded) {
            JobClaims.refusedGrant(job.id());
            throw JobProblems.grantRefused();
        }
        return new Stored(fileId, size, HexFormat.of().formatHex(digest.digest()));
    }

    /** Fills {@code buffer} as far as the body goes; 0 at its end. A body that broke off is invalid. */
    private static int read(InputStream body, byte[] buffer) {
        try {
            return body.readNBytes(buffer, 0, buffer.length);
        } catch (IOException cut) {
            throw JobProblems.invalid();
        }
    }

    /** The job with the live claim the grant belongs to; anything else is {@code job.grant_refused}. */
    private JobRow live(UUID jobId, UUID claimId, String grant) {
        byte[] presented = JobClaims.hashOf(vault, grant);
        JobRow row = storage.joined().find(jobId).orElse(null);
        if (row == null || !JobClaims.grantMatches(row, claimId, presented, clock.now())) {
            JobClaims.refusedGrant(jobId);
            throw JobProblems.grantRefused();
        }
        return row;
    }

    private void stillLive(UUID jobId, UUID claimId, byte[] presented) {
        JobRow row = storage.joined().find(jobId).orElse(null);
        if (!JobClaims.grantMatches(row, claimId, presented, clock.now())) {
            JobClaims.refusedGrant(jobId);
            throw JobProblems.grantRefused();
        }
    }

    /** A refused or cut output leaves nothing: its pieces, then its row (the sweep finishes what this misses). */
    private void discard(JobRow job, UUID fileId) {
        try {
            files.deleteFile(job.ownerId(), fileId);
            storage.separately(store -> store.deleteOutput(fileId));
        } catch (RuntimeException failure) {
            JobTrace.run(job.traceId(), () -> log.atWarn().addKeyValue("jobId", job.id()).addKeyValue("fileId", fileId)
                    .addKeyValue("errorType", failure.getClass().getName()).log("job.output_not_removed"));
        }
    }
}
