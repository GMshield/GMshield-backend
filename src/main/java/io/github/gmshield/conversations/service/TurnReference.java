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
 * From Story 5.1 part B a cited artifact's reference carries {@link Cited} with the artifact's version
 * ({@link #ARTIFACT}): it has no material, section path, page (0) or passage. From Story 5.2 part B it is removed once
 * its artifact is deleting or gone, worked out when it is read.
 * {@code toString} names the number and the page only.
 */
public record TurnReference(int number, String materialName, List<String> sectionPath, int page,
                            List<UUID> passageIds, boolean removed, String tier, Cited record) {

    /** A reference's kind: a passage place's, or a record's. */
    public static final String PASSAGE = "passage";
    public static final String RECORD = "record";
    /** Story 5.1 part B: a cited artifact's reference. */
    public static final String ARTIFACT = "artifact";

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

    /**
     * The record a reference names, as stored with it; for an artifact (Story 5.1 part B) its id and the number of the
     * version cited ({@code artifactVersion}, null for an extracted record). {@code toString} names the id, the type and
     * the version only.
     */
    public record Cited(UUID id, String type, String name, List<Field> fields, Integer artifactVersion) {
        public Cited {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(name, "name");
            fields = List.copyOf(fields);
        }

        /** An extracted record, as stored with its reference. */
        public Cited(UUID id, String type, String name, List<Field> fields) {
            this(id, type, name, fields, null);
        }

        @Override
        public String toString() {
            return "Cited[id=" + id + ", type=" + type + ", fields=" + fields.size() + ", artifactVersion="
                    + artifactVersion + "]";
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

    /** {@value #ARTIFACT} for an artifact's reference, {@value #RECORD} for a record's, else {@value #PASSAGE}. */
    public String kind() {
        if (record == null) {
            return PASSAGE;
        }
        return record.artifactVersion() == null ? RECORD : ARTIFACT;
    }

    @Override
    public String toString() {
        return "TurnReference[number=" + number + ", kind=" + kind() + ", page=" + page + ", removed=" + removed + "]";
    }
}
