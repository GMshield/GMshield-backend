package io.github.gmshield.conversations.service;

import io.github.gmshield.scope.service.CitedPlace;
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
 * of the reference row.
 */
final class TurnReferences {

    static final String ENTITY = "turn_reference";
    static final String FIELD_MATERIAL_NAME = "material_name";
    static final String FIELD_SECTION_PATH = "section_path";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TurnReferences() {
    }

    /** One reference before it is stored: its number, a place cited there (the first) and every passage cited there. */
    record Grouped(int number, CitedPlace place, List<UUID> passageIds) {
        Grouped {
            passageIds = List.copyOf(passageIds);
        }

        @Override
        public String toString() {
            return "Grouped[number=" + number + ", passages=" + passageIds.size() + "]";
        }
    }

    private record Key(UUID versionId, List<String> sectionPath, int page) {
    }

    /** The references of these cited places, given in the order first cited. */
    static List<Grouped> group(List<CitedPlace> places) {
        Map<Key, List<CitedPlace>> byPlace = new LinkedHashMap<>();
        for (CitedPlace place : places) {
            byPlace.computeIfAbsent(new Key(place.versionId(), place.sectionPath(), place.page()),
                    key -> new ArrayList<>()).add(place);
        }
        List<Grouped> grouped = new ArrayList<>(byPlace.size());
        for (List<CitedPlace> same : byPlace.values()) {
            grouped.add(new Grouped(grouped.size() + 1, same.getFirst(),
                    same.stream().map(CitedPlace::passageId).distinct().toList()));
        }
        return List.copyOf(grouped);
    }

    static Binding materialName(UUID referenceId, UUID owner) {
        return new Binding(ENTITY, FIELD_MATERIAL_NAME, referenceId, owner);
    }

    static Binding sectionPath(UUID referenceId, UUID owner) {
        return new Binding(ENTITY, FIELD_SECTION_PATH, referenceId, owner);
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
