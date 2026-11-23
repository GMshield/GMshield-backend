/**
 * The materials module's services: the resumable upload ({@link io.github.gmshield.materials.service.Uploads}) and its
 * sweep, the materials as the interface shows them, and the ingestion chain's job handler
 * ({@link io.github.gmshield.materials.service.IngestionSteps}). What other modules may use of {@code materials}
 * (Story 2.2) is the named interface {@code service}, which holds only
 * {@link io.github.gmshield.materials.service.ScopeReads} and its nested types ({@code Passage}, {@code Place}, and from
 * Story 2.7 a version's page and picture), and only the {@code scope} module may use it. From Story 4.2 it also holds
 * {@link io.github.gmshield.materials.service.ExtractedRecords} (the port the {@code knowledge} module implements to
 * store a version's records) and {@link io.github.gmshield.materials.service.ReadyVersions} (the {@code ready} versions
 * of a system's materials, whose records its record types count).
 */
package io.github.gmshield.materials.service;
