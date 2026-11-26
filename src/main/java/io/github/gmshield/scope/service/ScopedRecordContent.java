package io.github.gmshield.scope.service;

import java.util.List;
import java.util.Objects;

/**
 * A record read within a campaign's scope with its fields (Story 4.3 part C, an answer's record): the record as
 * {@link ScopedRecord} places it (its version, material, first page's place, passages there, core type, name and tier)
 * and its fields in stored order. From Story 5.1 part B an artifact's also carries its content ({@code null} for none,
 * and always for an extracted record), which a job reads as its last field and a stored reference never keeps.
 * {@code toString} names ids, the type, the page and the tier only, never the name, a field or the content.
 */
public record ScopedRecordContent(ScopedRecord record, List<Field> fields, String content) {

    /**
     * The name of the last field that carries an artifact's content wherever it is handed to the worker as a record
     * (Story 5.1 part B): its index line and an answer's records read, so both agree.
     */
    public static final String CONTENT_FIELD = "content";

    /** One field of the record: a name and a value. {@code toString} names neither. */
    public record Field(String name, String value) {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Field";
        }
    }

    public ScopedRecordContent {
        Objects.requireNonNull(record, "record");
        fields = List.copyOf(fields);
    }

    /** An extracted record's content: its fields, no free text. */
    public ScopedRecordContent(ScopedRecord record, List<Field> fields) {
        this(record, fields, null);
    }

    @Override
    public String toString() {
        return "ScopedRecordContent[record=" + record + ", fields=" + fields.size() + ", content=" + (content != null)
                + "]";
    }
}
