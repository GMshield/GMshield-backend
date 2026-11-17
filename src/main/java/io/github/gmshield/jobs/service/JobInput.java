package io.github.gmshield.jobs.service;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A file a handler hands to the worker for a claimed job (Story 1.15), opened by
 * {@link JobHandler#openInput}: its size in bytes, and a writer that streams it, decrypted piece by piece, never
 * whole in memory. {@link #writeTo} runs after the handler's transaction has ended, so it reads only the file store.
 */
public interface JobInput {

    long size();

    void writeTo(OutputStream out) throws IOException;
}
