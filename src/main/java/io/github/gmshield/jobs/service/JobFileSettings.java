package io.github.gmshield.jobs.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * The limits of the files a worker sends back (Story 1.15), Spring data sizes that the environment overrides
 * ({@code application.yaml}): one output at most 3 GiB ({@code GMSHIELD_JOB_OUTPUT_MAX_BYTES}), all the stored
 * outputs of one claim together at most 8 GiB ({@code GMSHIELD_JOB_OUTPUT_CLAIM_MAX_BYTES}). A start with a size that
 * is not positive, or an output limit above the claim's, is refused, naming the settings only.
 */
@Component
public class JobFileSettings {

    private final long outputMaxBytes;
    private final long claimMaxBytes;

    JobFileSettings(@Value("${gmshield.jobs.output-max-bytes}") DataSize outputMax,
                    @Value("${gmshield.jobs.output-claim-max-bytes}") DataSize claimMax) {
        if (outputMax == null || claimMax == null || outputMax.toBytes() < 1 || claimMax.toBytes() < outputMax.toBytes()) {
            throw new IllegalStateException("setting.invalid: GMSHIELD_JOB_OUTPUT_MAX_BYTES,"
                    + " GMSHIELD_JOB_OUTPUT_CLAIM_MAX_BYTES");
        }
        this.outputMaxBytes = outputMax.toBytes();
        this.claimMaxBytes = claimMax.toBytes();
    }

    /** The largest single output, in bytes. */
    public long outputMaxBytes() {
        return outputMaxBytes;
    }

    /** The most one claim may store in outputs, in bytes. */
    public long claimMaxBytes() {
        return claimMaxBytes;
    }
}
