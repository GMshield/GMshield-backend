package io.github.gmshield.jobs.service;

import java.util.List;
import java.util.UUID;

/**
 * A numbered batch of answer events the worker posted under one claim (Story 2.4): its claim, its number (from 1 per
 * claim) and its events, checked. A job's handler stores it through {@link JobHandler#events}.
 */
public record JobEventBatch(UUID claimId, int number, List<JobEvent> events) {

    public JobEventBatch {
        events = List.copyOf(events);
    }

    @Override
    public String toString() {
        return "JobEventBatch[claimId=" + claimId + ", number=" + number + ", events=" + events.size() + "]";
    }
}
