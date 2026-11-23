package io.github.gmshield.conversations.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A source reference of a completed turn as the GM's stream sends it (Story 2.5): its number (1..n, by first citation),
 * the material's name, the section path (outermost first), the page, the ids of the cited passages at that place,
 * whether its source has been removed since, and (Story 3.7) the tier its source had in the turn's scope when the
 * answer was written ({@code null} for a reference stored before then). From Story 4.3 part C a cited record's
 * reference also carries {@link Cited} (null on a passage's): the record's id, core type key, name and fields as they
 * were when the answer was written; its place is the record's first page and its passages the record's passages there.
 * {@code toString} names the number and the page only.
 */
public record TurnReference(int number, String materialName, List<String> sectionPath, int page,
                            List<UUID> passageIds, boolean removed, String tier, Cited record) {

    /** A reference's kind: a passage place's, or a record's. */
    public static final String PASSAGE = "passage";
    public static final String RECORD = "record";

    /** One field of a cited record: a name and a value. {@code toString} names neither. */
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

    /** The record a reference names, as stored with it. {@code toString} names the id and the type only. */
    public record Cited(UUID id, String type, String name, List<Field> fields) {
        public Cited {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(name, "name");
            fields = List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "Cited[id=" + id + ", type=" + type + ", fields=" + fields.size() + "]";
        }
    }

    public TurnReference {
        sectionPath = List.copyOf(sectionPath);
        passageIds = List.copyOf(passageIds);
    }

    /** A passage place's reference: no record. */
    public TurnReference(int number, String materialName, List<String> sectionPath, int page, List<UUID> passageIds,
                         boolean removed, String tier) {
        this(number, materialName, sectionPath, page, passageIds, removed, tier, null);
    }

    /** {@value #RECORD} for a record's reference, else {@value #PASSAGE}. */
    public String kind() {
        return record == null ? PASSAGE : RECORD;
    }

    @Override
    public String toString() {
        return "TurnReference[number=" + number + ", kind=" + kind() + ", page=" + page + ", removed=" + removed + "]";
    }
}
