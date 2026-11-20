/**
 * The library's tables, {@code system} and {@code campaign} (V4), through JPA entities and JPQL only: Hibernate
 * fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work, so no query here names an
 * owner, and none can reach another owner's rows. Names are vault ciphertexts. From Story 3.1 (V21) a system also has an
 * edition and a description (ciphertexts) and a version (Hibernate {@code @Version}); a write that the name key or the
 * version refuses is a {@code LibraryConflict}.
 */
package io.github.gmshield.library.data;
