package io.github.gmshield.jobs.data;

import java.util.Optional;
import java.util.UUID;

/** Test mode only: the table {@code job_probe}, written in the transaction that creates the probe job. */
public interface ProbeJobStore {

    void insert(ProbeJobRow row);

    Optional<ProbeJobRow> find(UUID jobId);
}
