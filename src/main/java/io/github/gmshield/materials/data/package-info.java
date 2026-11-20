/**
 * The materials tables (V6, V7, V9 to V15). {@code material}, {@code material_version}, {@code material_system},
 * {@code ingestion_step}, {@code version_file}, {@code page_layer}, {@code page_outcome}, {@code passage},
 * {@code version_outline}, {@code page_view}, {@code index_change} and {@code index_removal} through JPA entities and
 * JPQL only:
 * Hibernate fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work. {@code upload} is not
 * a library table: it holds no content, and is read and written through {@code JdbcClient}, always by its id and
 * owner, or by the sweep, which has no owner. The one exception (Story 1.20): {@link PendingRemovals} reads, through
 * {@code JdbcClient}, the owner and id of each {@code material_version} whose removal is pending, for the removal
 * sweep, which has no owner; it reads no content and writes nothing. The second (Story 2.1): {@link StaleIndexes} reads
 * the owners of the {@code ready} versions indexed under another recipe, for the reconcile, which has no owner either.
 */
package io.github.gmshield.materials.data;
