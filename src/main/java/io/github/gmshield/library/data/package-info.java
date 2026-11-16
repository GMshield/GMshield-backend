/**
 * The library's tables, {@code system} and {@code campaign} (V4), through JPA entities and JPQL only: Hibernate
 * fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work, so no query here names an
 * owner, and none can reach another owner's rows. Names are vault ciphertexts.
 */
package io.github.gmshield.library.data;
