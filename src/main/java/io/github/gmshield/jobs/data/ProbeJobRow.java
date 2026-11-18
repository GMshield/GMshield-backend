package io.github.gmshield.jobs.data;

import java.util.UUID;

/** Test mode only: what a probe job does, kept beside its job (never in the job row). */
public record ProbeJobRow(UUID jobId, int workMs, int failTimes) {
}
