/**
 * The materials tables (V6, V7, V9). {@code material}, {@code material_version}, {@code material_system},
 * {@code ingestion_step} and {@code version_file} through JPA entities and JPQL only:
 * Hibernate fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work. {@code upload} is not
 * a library table: it holds no content, and is read and written through {@code JdbcClient}, always by its id and
 * owner, or by the sweep, which has no owner.
 */
package io.github.gmshield.materials.data;
