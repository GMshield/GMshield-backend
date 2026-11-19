package io.github.gmshield.limits.service;

import io.github.gmshield.jobs.service.CallUsage;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.ModelChoice;
import io.github.gmshield.jobs.service.UsageReports;
import io.github.gmshield.limits.data.UsageRow;
import io.github.gmshield.limits.data.UsageStore;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The usage of each model call (Story 2.4, AD-32), the {@code jobs} module's {@link UsageReports}: one
 * {@code model_call_usage} row per worker call id, written once whatever how often the worker reports it, with the job,
 * its owner, the attempt's provider, model and tier, the provider's response id and the token counts. No price until
 * Story 9.2. Lines carry ids and counts only.
 */
@Service
public class ModelUsage implements UsageReports {

    private static final Logger log = LoggerFactory.getLogger(ModelUsage.class);

    private final UsageStore store;
    private final Clock clock;

    ModelUsage(UsageStore store, ObjectProvider<Clock> clock) {
        this.store = store;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public void record(JobRef job, ModelChoice model, List<CallUsage> usage) {
        if (!OwnerContext.require().equals(job.ownerId())) {
            throw new IllegalStateException("limits.owner_mismatch");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int written = 0;
        for (CallUsage call : usage) {
            written += store.insertOnce(new UsageRow(call.callId(), job.ownerId(), job.id(), model.provider(),
                    model.model(), model.tier(), call.responseId(), call.input(), call.cacheRead(), call.cacheWrite(),
                    call.output(), call.reasoning(), now)) ? 1 : 0;
        }
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("modelProvider", model.provider())
                .addKeyValue("modelTier", model.tier()).addKeyValue("calls", usage.size())
                .addKeyValue("recorded", written)
                .addKeyValue("inputTokens", usage.stream().mapToLong(CallUsage::input).sum())
                .addKeyValue("outputTokens", usage.stream().mapToLong(CallUsage::output).sum())
                .log("model.usage_recorded");
    }
}
