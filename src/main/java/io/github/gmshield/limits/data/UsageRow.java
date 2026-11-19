package io.github.gmshield.limits.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code model_call_usage} (Story 2.4): a model call's tokens, keyed by the worker's call id, and whether
 * they are the worker's estimate (Story 2.6).
 */
public record UsageRow(UUID callId, UUID ownerId, UUID jobId, String provider, String model, String tier,
                       String responseId, long input, long cacheRead, long cacheWrite, long output, long reasoning,
                       boolean estimated, Instant createdAt) {
}
