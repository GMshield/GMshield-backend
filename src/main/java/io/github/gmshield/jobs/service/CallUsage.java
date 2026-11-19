package io.github.gmshield.jobs.service;

import java.util.UUID;

/**
 * One model call's tokens as the worker reports them (Story 2.4, AD-32): the worker's call id, the provider's response
 * id when there is one, input (with the cache reads and writes), output (with the reasoning).
 */
public record CallUsage(UUID callId, String responseId, long input, long cacheRead, long cacheWrite, long output,
                        long reasoning) {
}
