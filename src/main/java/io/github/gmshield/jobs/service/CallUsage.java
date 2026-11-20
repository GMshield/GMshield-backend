package io.github.gmshield.jobs.service;

import java.util.UUID;

/**
 * One model call's tokens as the worker reports them (Story 2.4, AD-32): the worker's call id, the provider's response
 * id when there is one, input (with the cache reads and writes), output (with the reasoning), and whether the counts
 * are the worker's estimate (Story 2.6: a call stopped before the provider sent its usage).
 */
public record CallUsage(UUID callId, String responseId, long input, long cacheRead, long cacheWrite, long output,
                        long reasoning, boolean estimated) {
}
