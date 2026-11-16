/**
 * The jobs module's stores: the table {@code job} through {@code JdbcClient} (not a library table: no
 * {@code @TenantId}, no content), the two Redis work streams, and the test-mode table {@code job_probe}.
 */
package io.github.gmshield.jobs.data;
