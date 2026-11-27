/**
 * Story 5.1: {@code record_version} (V43), the numbered versions of an artifact's content, through a JPA entity and JPQL
 * only: Hibernate fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work, so no query here
 * names an owner and none can reach another owner's rows. The name, fields and content are vault ciphertexts; a version
 * names its record with the owner, so it can never belong to another owner's record. Story 5.4 part A adds
 * {@code artifact_reference}, Story 5.6 part A {@code artifact_share} (an artifact and a campaign it is shared with).
 */
package io.github.gmshield.artifacts.data;
