/**
 * The conversation tables ({@code conversation}, {@code turn}, {@code turn_event_batch}, V17) through JPA entities and
 * JPQL only: Hibernate fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work, so no query
 * here names an owner. A question and a batch's events are vault ciphertexts.
 */
package io.github.gmshield.conversations.data;
