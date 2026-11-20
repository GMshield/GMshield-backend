package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code material_version}: its state ({@code uploading}, {@code waiting}, ... {@code discarded}), and,
 * once its file is whole and verified, the file's format and size in bytes (null until then). From Story 1.15, what
 * its ingestion chain reported: the page total and the worker's pipeline version (null until the first report), and
 * the failure code of a version that ended {@code failed}. From Story 1.20, {@code removalPending}: a discarded
 * version whose removal has not finished. From Story 2.1, {@code indexRecipe}: the worker's index recipe every index job
 * of its current set reported (null until the last of them completed).
 */
public record VersionRow(UUID id, UUID ownerId, UUID materialId, String state, String format, Long sizeBytes,
                         Instant createdAt, Instant updatedAt, Integer pageTotal, String pipelineVersion,
                         String failureCode, boolean removalPending, String indexRecipe) {

    /** A version not indexed. */
    public VersionRow(UUID id, UUID ownerId, UUID materialId, String state, String format, Long sizeBytes,
                      Instant createdAt, Instant updatedAt, Integer pageTotal, String pipelineVersion,
                      String failureCode, boolean removalPending) {
        this(id, ownerId, materialId, state, format, sizeBytes, createdAt, updatedAt, pageTotal, pipelineVersion,
                failureCode, removalPending, null);
    }

    /** A version with no removal pending. */
    public VersionRow(UUID id, UUID ownerId, UUID materialId, String state, String format, Long sizeBytes,
                      Instant createdAt, Instant updatedAt, Integer pageTotal, String pipelineVersion,
                      String failureCode) {
        this(id, ownerId, materialId, state, format, sizeBytes, createdAt, updatedAt, pageTotal, pipelineVersion,
                failureCode, false);
    }

    /** A version without anything from ingestion yet. */
    public VersionRow(UUID id, UUID ownerId, UUID materialId, String state, String format, Long sizeBytes,
                      Instant createdAt, Instant updatedAt) {
        this(id, ownerId, materialId, state, format, sizeBytes, createdAt, updatedAt, null, null, null);
    }
}
