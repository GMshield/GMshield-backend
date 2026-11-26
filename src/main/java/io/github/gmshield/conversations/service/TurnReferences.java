package io.github.gmshield.conversations.service;

import io.github.gmshield.scope.service.CitedPlace;
import io.github.gmshield.scope.service.ScopedRecord;
import io.github.gmshield.scope.service.ScopedRecordContent;
import io.github.gmshield.vault.service.Binding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * How a turn's source references are made and stored (Story 2.5): the cited places become one reference per distinct
 * place {@code (version, section path, page)}, numbered 1..n by first citation, each with the ids of the passages cited
 * there in the order first cited. The material's name and the section path (a JSON array) are encrypted under bindings
 * of the reference row. From Story 4.3 part C a cited record is a reference of its own, whatever place it shares with
 * other citations: its key includes the record's id, its place is its first passage's on its first page and its
 * passages are its passages there; its name and fields (a JSON array of {@code {name, value}}) are encrypted under
 * bindings of the reference row too. From Story 5.1 part B a cited artifact is a reference of its own as well: it has no
 * place (no version, section path or passage, page 1 to group by), and its name and fields are stored as a record's.
 */
final class TurnReferences {

    static final String ENTITY = "turn_reference";
    static final String FIELD_MATERIAL_NAME = "material_name";
    static final String FIELD_SECTION_PATH = "section_path";
    static final String FIELD_RECORD_NAME = "record_name";
    static final String FIELD_RECORD_FIELDS = "record_fields";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TurnReferences() {
    }

    /**
     * One citation as the scope placed it: a passage's place ({@code record} null), or a record with its content (its
     * place is its first passage's on its first page). {@code toString} names ids only.
     */
    record Cited(CitedPlace place, ScopedRecordContent record) {
        static Cited passage(CitedPlace place) {
            return new Cited(place, null);
        }

        /**
         * A cited record: its place is its first passage's on its first page. A cited artifact (Story 5.1 part B) has no
         * passage and no place but its tier.
         */
        static Cited record(ScopedRecordContent content) {
            ScopedRecord record = content.record();
            if (record.artifact()) {
                return new Cited(new CitedPlace(null, null, null, null, List.of(), record.page(), record.tier()),
                        content);
            }
            return new Cited(new CitedPlace(record.passageIds().getFirst(), record.versionId(), record.materialId(),
                    record.materialName(), record.sectionPath(), record.page(), record.tier()), content);
        }

        @Override
        public String toString() {
            return "Cited[place=" + place + ", record=" + (record == null ? null : record.record().recordId()) + "]";
        }
    }

    /**
     * One reference before it is stored: its number, a place cited there (the first), every passage cited there (a
     * record's: its passages on its first page) and, for a record's reference, the record.
     */
    record Grouped(int number, CitedPlace place, List<UUID> passageIds, ScopedRecordContent record) {
        Grouped {
            passageIds = List.copyOf(passageIds);
        }

        @Override
        public String toString() {
            return "Grouped[number=" + number + ", passages=" + passageIds.size() + ", record="
                    + (record == null ? null : record.record().recordId()) + "]";
        }
    }

    private record Key(UUID versionId, List<String> sectionPath, int page, UUID recordId) {
    }

    /** The references of these cited places, given in the order first cited. */
    static List<Grouped> group(List<CitedPlace> places) {
        return groupCited(places.stream().map(Cited::passage).toList());
    }

    /** The references of these citations of passages and records, given in the order first cited. */
    static List<Grouped> groupCited(List<Cited> cited) {
        Map<Key, List<Cited>> byPlace = new LinkedHashMap<>();
        for (Cited one : cited) {
            CitedPlace place = one.place();
            byPlace.computeIfAbsent(new Key(place.versionId(), place.sectionPath(), place.page(), one.record() == null
                    ? null : one.record().record().recordId()), key -> new ArrayList<>()).add(one);
        }
        List<Grouped> grouped = new ArrayList<>(byPlace.size());
        for (List<Cited> same : byPlace.values()) {
            Cited first = same.getFirst();
            List<UUID> passageIds = first.record() != null ? first.record().record().passageIds()
                    : same.stream().map(one -> one.place().passageId()).distinct().toList();
            grouped.add(new Grouped(grouped.size() + 1, first.place(), passageIds, first.record()));
        }
        return List.copyOf(grouped);
    }

    static Binding materialName(UUID referenceId, UUID owner) {
        return new Binding(ENTITY, FIELD_MATERIAL_NAME, referenceId, owner);
    }

    static Binding sectionPath(UUID referenceId, UUID owner) {
        return new Binding(ENTITY, FIELD_SECTION_PATH, referenceId, owner);
    }

    static Binding recordName(UUID referenceId, UUID owner) {
        return new Binding(ENTITY, FIELD_RECORD_NAME, referenceId, owner);
    }

    static Binding recordFields(UUID referenceId, UUID owner) {
        return new Binding(ENTITY, FIELD_RECORD_FIELDS, referenceId, owner);
    }

    /** A record's fields as stored with its reference: a JSON array of {@code {name, value}}, in order. */
    static byte[] fieldsJson(List<ScopedRecordContent.Field> fields) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        fields.forEach(field -> array.addObject().put("name", field.name()).put("value", field.value()));
        return JSON.writeValueAsBytes(array);
    }

    /** The fields {@link #fieldsJson} stored, in order. */
    static List<TurnReference.Field> fields(byte[] json) {
        List<TurnReference.Field> fields = new ArrayList<>();
        for (JsonNode field : JSON.readTree(json)) {
            fields.add(new TurnReference.Field(field.path("name").asString(), field.path("value").asString()));
        }
        return List.copyOf(fields);
    }

    static byte[] pathJson(List<String> sectionPath) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        sectionPath.forEach(array::add);
        return JSON.writeValueAsBytes(array);
    }

    static List<String> path(byte[] json) {
        List<String> path = new ArrayList<>();
        for (JsonNode title : JSON.readTree(json)) {
            path.add(title.asString());
        }
        return List.copyOf(path);
    }
}
