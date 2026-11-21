/**
 * The library's tables, {@code system} and {@code campaign} (V4), through JPA entities and JPQL only: Hibernate
 * fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work, so no query here names an
 * owner, and none can reach another owner's rows. Names are vault ciphertexts. From Story 3.1 (V21) a system also has an
 * edition and a description (ciphertexts) and a version (Hibernate {@code @Version}); a write that the name key or the
 * version refuses is a {@code LibraryConflict}. From Story 3.1 part B (V22) a system has a state ({@code ready} or
 * {@code deleting}) and every read but the deletion's own takes ready systems only. {@code deletion} is not a library
 * table: it holds ids and times only and is read and written through {@code JdbcClient} ({@link DeletionStore}), with
 * the owner named in every write and read by id; the removal sweep's list alone reads across owners. From Story 3.2
 * (V23) a campaign has a version, an archive time, a choice time and a name fingerprint unique per owner and system;
 * {@code campaign_fingerprint_backfill} is not a library table either (owner ids only, {@link FingerprintBackfill}).
 */
package io.github.gmshield.library.data;
