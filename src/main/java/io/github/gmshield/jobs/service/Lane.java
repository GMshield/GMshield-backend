package io.github.gmshield.jobs.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * Where a job is offered. The worker serves the interactive stream first, with capacity of its own; ingestion jobs
 * go out one at a time, alternating between owners.
 */
public enum Lane {

    INTERACTIVE("interactive", "gmshield:work:interactive"),
    INGESTION("ingestion", "gmshield:work:ingestion");

    private final String code;
    private final String stream;

    Lane(String code, String stream) {
        this.code = code;
        this.stream = stream;
    }

    /** The stored word ({@code job.lane}). */
    public String code() {
        return code;
    }

    /** The Redis stream its jobs are offered on. */
    public String stream() {
        return stream;
    }

    public static Optional<Lane> of(String code) {
        return Arrays.stream(values()).filter(lane -> lane.code.equals(code)).findFirst();
    }
}
