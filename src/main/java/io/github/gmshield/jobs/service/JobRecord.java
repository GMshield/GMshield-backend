package io.github.gmshield.jobs.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A record a job's handler serves to the worker by id (Story 4.3 part C, internal contract 1.19.0): its id, the tier of
 * its source ({@code campaign}, {@code system} or {@code other}), its core type's key, its name, its fields in order,
 * its first page and the section path of its first passage there (outermost first). {@code toString} names the id, the
 * tier, the type and the page only, never the name or a field.
 */
public record JobRecord(UUID id, String tier, String type, String name, List<Field> fields, int page,
                        List<String> sectionPath) {

    /** One field: a name and a value. {@code toString} names neither. */
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

    public JobRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        fields = List.copyOf(fields);
        sectionPath = List.copyOf(sectionPath);
    }

    @Override
    public String toString() {
        return "JobRecord[id=" + id + ", tier=" + tier + ", type=" + type + ", page=" + page + "]";
    }
}
