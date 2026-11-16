/**
 * Background work that survives a restart (Story 1.12, AD-15). A job is a row of {@code job} (Postgres), written
 * in the transaction that causes it and the only record of its state, order, attempts and outcome. After the
 * commit its id is offered on a Redis stream ({@code gmshield:work:interactive} or {@code gmshield:work:ingestion},
 * the field {@code job} only, consumer group {@code workers}). The worker claims it through {@code /internal/v1}
 * and gets a grant it sends back on every renewal and on the outcome; the backend alone decides retries,
 * re-offers jobs Redis lost, offers one ingestion job at a time, alternating between owners, and trims the streams.
 * Feature modules register what a kind of job does through {@link io.github.gmshield.jobs.service.JobHandler}:
 * this module depends on no feature module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Jobs",
        allowedDependencies = {"shared :: service", "vault :: service"})
package io.github.gmshield.jobs;
