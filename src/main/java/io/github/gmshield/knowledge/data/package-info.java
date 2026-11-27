/**
 * Story 4.1: {@code record_type} (V34), through JPA entities and JPQL only: Hibernate fills and filters {@code owner_id}
 * ({@code @TenantId}) from the owner of the work, so no query here names an owner and none can reach another owner's
 * rows. Names are vault ciphertexts beside their keyed fingerprint, unique per owner and system, as the core key is; a
 * write those keys or the version refuse is a {@link io.github.gmshield.knowledge.data.KnowledgeConflict}. The fill of
 * the systems from before V34 lists them across owners through {@code JdbcClient} (ids only,
 * {@link io.github.gmshield.knowledge.data.MissingCoreTypes}), then works as each owner. Story 4.2: {@code record} and
 * {@code record_passage} (V35, {@link io.github.gmshield.knowledge.data.RecordStore}), the same way: a record's name
 * and fields are vault ciphertexts, its links name passages of its owner. Story 5.1 (V43): an artifact's identity is a
 * {@code record} row with a campaign and no version ({@link io.github.gmshield.knowledge.data.ArtifactRow}). Story 5.5
 * part A (V50): {@code record_relation} ({@link io.github.gmshield.knowledge.data.RecordRelationStore}), a link between
 * two records of the owner, removed with either; part B (V51) adds the kind {@code related}.
 */
package io.github.gmshield.knowledge.data;
