package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code page_layer} or {@code page_outcome} (Story 1.16): the pages {@code firstPage} to {@code lastPage}
 * (inclusive) of a version, and their layer ({@value #TEXT} or {@value #IMAGE}) or their outcome
 * ({@value #READ_DIRECTLY}, {@value #READ_BY_OCR} or {@value #UNREADABLE}).
 */
public record PageRangeRow(UUID id, UUID ownerId, UUID versionId, int firstPage, int lastPage, String value,
                           Instant createdAt) {

    public static final String TEXT = "text";
    public static final String IMAGE = "image";
    public static final String READ_DIRECTLY = "read_directly";
    public static final String READ_BY_OCR = "read_by_ocr";
    public static final String UNREADABLE = "unreadable";

    /** How many pages the range holds. */
    public int pages() {
        return lastPage - firstPage + 1;
    }
}
