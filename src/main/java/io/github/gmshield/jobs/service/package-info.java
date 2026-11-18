/**
 * What other modules may use of {@code jobs}: {@link io.github.gmshield.jobs.service.Jobs} creates a job inside the
 * caller's transaction, and a feature module implements {@link io.github.gmshield.jobs.service.JobHandler} for each
 * kind of job it owns.
 */
@org.springframework.modulith.NamedInterface("service")
package io.github.gmshield.jobs.service;
