package io.github.gmshield.jobs.service;

/**
 * Thrown by {@link JobHandler#completed} when the worker's result cannot be recorded (a missing field, an output that
 * is not the job's...): the job's change rolls back with the handler's, and the outcome is answered
 * {@code job.result_invalid} (400). The job stays running; its claim lapses and the backend decides the retry. The
 * message names nothing from the result.
 */
public class JobResultInvalid extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JobResultInvalid(String reason) {
        super(reason);
    }
}
