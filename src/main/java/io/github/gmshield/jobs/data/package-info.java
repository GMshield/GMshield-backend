/**
 * The jobs module's stores: the table {@code job} through {@code JdbcClient} (not a library table: no
 * {@code @TenantId}, no content), the files a worker sends back for a job ({@code job_output}, Story 1.15: ids and
 * sizes only), the two Redis work streams, and the test-mode table {@code job_probe}.
 */
package io.github.gmshield.jobs.data;
