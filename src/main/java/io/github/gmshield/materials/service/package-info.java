/**
 * The materials module's services: the resumable upload ({@link io.github.gmshield.materials.service.Uploads}) and its
 * sweep, the materials as the interface shows them, and the ingestion chain's job handler
 * ({@link io.github.gmshield.materials.service.IngestionSteps}). What other modules may use of {@code materials}
 * (Story 2.2) is the named interface {@code service}, which holds only
 * {@link io.github.gmshield.materials.service.ScopeReads} and its {@code Passage}, and only the {@code scope} module
 * may use it.
 */
package io.github.gmshield.materials.service;
