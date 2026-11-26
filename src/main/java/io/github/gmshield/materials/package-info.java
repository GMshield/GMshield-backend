/**
 * Materials (Story 1.13 onward): what a GM adds to the library, starting with the resumable upload of its file. A
 * {@code material} and its {@code material_version}s are library tables (owner column, Hibernate {@code @TenantId},
 * JPA and JPQL only, names encrypted through {@code vault}); an {@code upload} is a working row of the tus protocol
 * that holds no content, read and written through JDBC. The file itself goes to the {@code vault} file store,
 * encrypted piece by piece as it arrives. From Story 1.15 a completed PDF starts its ingestion chain, a job of the
 * {@code jobs} module per step; the steps ({@code ingestion_step}) and the files they store for the version
 * ({@code version_file}) are library tables too. From Story 1.20 the GM accepts, reprocesses, retries or discards a
 * version; a discarded version is removed by a recorded process. From Story 2.1 an accepted version gets index jobs
 * ({@code index_change}) and a discarded one the removal of its points ({@code index_removal}). The reads of a library
 * table without an owner are two lists of ids: the removal sweep's versions whose removal is pending
 * ({@code data.PendingRemovals}) and the reconcile's owners with a version indexed under another recipe
 * ({@code data.StaleIndexes}). Another owner's item is "not found", exactly like an unknown one.
 *
 * <p>From Story 2.2, {@code service.ScopeReads} is the one public read of library items for a campaign: the ready
 * versions by campaign and by system, the pending passage ids of a set of versions, and checked passage reads by id,
 * over the owner-scoped store queries {@code readyOfCampaign}, {@code readyOfSystem}, {@code passageIds} and
 * {@code findPassages}; from Story 2.7, a page and picture of a given cited version, through the review ground's
 * read. It is this module's only named interface, and only the {@code scope} module may use it
 * ({@code ModuleBoundaryTest}).
 *
 * <p>From Story 3.1 part B, {@code service.SystemMaterialsDeletion} is this module's part of a system's deletion
 * ({@code library.service.SystemDeletionHandler}): it cancels and discards the versions of the materials that go, and
 * later removes them, their rows and files, after one {@code index.removal} per version.
 *
 * <p>From Story 4.5 part B, a record the GM deleted loses its index point by a job of kind {@code index.record_removal}
 * ({@code service.RecordRemovals}, created through {@code service.RecordIndexing}), whose input is a
 * {@code record_removal} row (V42); the removal retry sweep retries it as it does {@code index.removal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Materials",
        allowedDependencies = {"shared :: service", "vault :: service", "jobs :: service", "library :: service"})
package io.github.gmshield.materials;
