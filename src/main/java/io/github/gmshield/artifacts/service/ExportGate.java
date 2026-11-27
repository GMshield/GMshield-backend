package io.github.gmshield.artifacts.service;

import java.util.concurrent.Semaphore;

/**
 * How many PDF and JPG exports render at once (Story 5.7, Dalton's limit): at most {@value #MAX}. A third is refused at
 * once, never queued ({@link #tryEnter} answers false): {@link ArtifactExports#EXPORT_BUSY}.
 */
final class ExportGate {

    static final int MAX = 2;
    private final Semaphore renders = new Semaphore(MAX);

    /** True when a render may start now; it must then {@link #leave}. */
    boolean tryEnter() {
        return renders.tryAcquire();
    }

    void leave() {
        renders.release();
    }
}
