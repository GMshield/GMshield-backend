package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.VersionRow;

/**
 * Starts the ingestion chain of a version whose upload just completed (Story 1.15), inside the transaction that makes
 * the version {@code waiting}, as its owner. {@link IngestionSteps} in the application; a test may record the calls.
 */
@FunctionalInterface
interface IngestionStart {

    /** The first step for {@code version}, whose raw file is {@code upload}; nothing for a format not ingested yet. */
    void start(VersionRow version, UploadRow upload);
}
