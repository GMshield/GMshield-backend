package io.github.gmshield.jobs.service;

import java.util.UUID;

/**
 * A file the worker sent back for a job and its handler adopted ({@link Jobs#adoptOutput}): from then on it belongs
 * to the handler's module, in the file store under the job's owner and {@code fileId}, in {@code pieceCount} pieces.
 */
public record JobOutput(UUID fileId, long sizeBytes, int pieceCount) {
}
