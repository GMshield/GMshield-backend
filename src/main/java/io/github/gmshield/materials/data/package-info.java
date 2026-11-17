/**
 * The materials tables (V6). {@code material} and {@code material_version} through JPA entities and JPQL only:
 * Hibernate fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work. {@code upload} is not
 * a library table: it holds no content, and is read and written through {@code JdbcClient}, always by its id and
 * owner, or by the sweep, which has no owner.
 */
package io.github.gmshield.materials.data;
