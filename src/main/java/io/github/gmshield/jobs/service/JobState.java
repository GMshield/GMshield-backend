package io.github.gmshield.jobs.service;

/** The stored states of a job ({@code job.state}). */
final class JobState {

    static final String WAITING = "waiting";
    static final String OFFERED = "offered";
    static final String RUNNING = "running";
    static final String DONE = "done";
    static final String FAILED = "failed";
    static final String CANCELLED = "cancelled";

    private JobState() {
    }
}
