package io.github.gmshield.scope.service;

import java.io.IOException;
import java.io.OutputStream;

/** A cited page's picture (Story 2.7): WebP, its size in bytes, streamed one decrypted piece at a time. */
public interface SourcePicture {

    long size();

    void writeTo(OutputStream out) throws IOException;
}
