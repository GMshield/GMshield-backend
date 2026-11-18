package io.github.gmshield.jobs.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The job timings and limits, Spring properties that the environment overrides ({@code application.yaml}):
 * a claim lapses 90 s after its last renewal ({@code GMSHIELD_JOB_CLAIM_LAPSE}), the worker renews every 30 s
 * ({@code GMSHIELD_JOB_RENEW_EVERY}), a job has 3 attempts ({@code GMSHIELD_JOB_MAX_ATTEMPTS}), an offer that no
 * worker claimed is made again after 2 min ({@code GMSHIELD_JOB_REOFFER_AFTER}), and the sweep runs every 10 s
 * ({@code GMSHIELD_JOB_SWEEP_EVERY}). A start with values out of order is refused, naming the settings only.
 */
@Component
public class JobSettings {

    /** How long a message stays in a stream: far past the re-offer delay, so it was answered or superseded. */
    static final Duration STREAM_RETENTION = Duration.ofHours(1);

    private final Duration claimLapse;
    private final Duration renewEvery;
    private final int maxAttempts;
    private final Duration reofferAfter;

    JobSettings(@Value("${gmshield.jobs.claim-lapse}") Duration claimLapse,
                @Value("${gmshield.jobs.renew-every}") Duration renewEvery,
                @Value("${gmshield.jobs.max-attempts}") int maxAttempts,
                @Value("${gmshield.jobs.reoffer-after}") Duration reofferAfter,
                @Value("${gmshield.jobs.sweep-every}") Duration sweepEvery) {
        boolean ordered = positive(renewEvery) && positive(claimLapse) && renewEvery.compareTo(claimLapse) < 0
                && positive(reofferAfter) && reofferAfter.compareTo(STREAM_RETENTION) < 0 && positive(sweepEvery)
                && maxAttempts >= 1 && maxAttempts <= 100;
        if (!ordered) {
            throw new IllegalStateException("setting.invalid: GMSHIELD_JOB_CLAIM_LAPSE, GMSHIELD_JOB_RENEW_EVERY,"
                    + " GMSHIELD_JOB_MAX_ATTEMPTS, GMSHIELD_JOB_REOFFER_AFTER, GMSHIELD_JOB_SWEEP_EVERY");
        }
        this.claimLapse = claimLapse;
        this.renewEvery = renewEvery;
        this.maxAttempts = maxAttempts;
        this.reofferAfter = reofferAfter;
    }

    public Duration claimLapse() {
        return claimLapse;
    }

    public Duration renewEvery() {
        return renewEvery;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration reofferAfter() {
        return reofferAfter;
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
