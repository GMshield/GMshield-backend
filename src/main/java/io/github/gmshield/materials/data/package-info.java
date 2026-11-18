/**
 * The materials tables (V6, V7, V9 to V13). {@code material}, {@code material_version}, {@code material_system},
 * {@code ingestion_step}, {@code version_file}, {@code page_layer}, {@code page_outcome}, {@code passage},
 * {@code version_outline} and {@code page_view} through JPA entities and JPQL only:
 * Hibernate fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work. {@code upload} is not
 * a library table: it holds no content, and is read and written through {@code JdbcClient}, always by its id and
 * owner, or by the sweep, which has no owner. The one exception (Story 1.20): {@link PendingRemovals} reads, through
 * {@code JdbcClient}, the owner and id of each {@code material_version} whose removal is pending, for the removal
 * sweep, which has no owner; it reads no content and writes nothing.
 */
package io.github.gmshield.materials.data;
