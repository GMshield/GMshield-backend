package io.github.gmshield.scope.service;

import java.util.List;
import java.util.Objects;

/**
 * A record read within a campaign's scope with its fields (Story 4.3 part C, an answer's record): the record as
 * {@link ScopedRecord} places it (its version, material, first page's place, passages there, core type, name and tier)
 * and its fields in stored order. {@code toString} names ids, the type, the page and the tier only, never the name or a
 * field.
 */
public record ScopedRecordContent(ScopedRecord record, List<Field> fields) {

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

    @Override
    public String toString() {
        return "ScopedRecordContent[record=" + record + ", fields=" + fields.size() + "]";
    }
}
