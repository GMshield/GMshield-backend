/**
 * The conversation tables ({@code conversation}, {@code turn}, {@code turn_event_batch}, V17; {@code turn_reference},
 * V18; {@code turn_material_off}, V27; {@code search} and {@code search_result}, V29, Story 3.9) through JPA entities and
 * JPQL only: Hibernate fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work, so no query here
 * names an owner. A question, a batch's events, a reference's material name and section path, and a search's query are
 * vault ciphertexts.
 */
package io.github.gmshield.conversations.data;
