package io.github.gmshield.materials.service;

import io.github.gmshield.vault.service.Binding;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * How passages and outlines are stored (Story 1.18): the bindings of their encrypted fields, and the JSON of a
 * passage's place ({@code {"sectionPath": [...], "boxes": [[l, t, r, b], ...]}} or {@code {"sectionPath": [...],
 * "range": [start, end]}}) and of an outline ({@code [{"level", "title", "page"}, ...]}).
 */
final class Passages {

    static final String PASSAGE = "passage";
    static final String FIELD_TEXT = "text";
    static final String FIELD_PLACE = "place";
    static final String OUTLINE = "version_outline";
    static final String FIELD_OUTLINE = "outline";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Passages() {
    }

    static Binding text(UUID passageId, UUID owner) {
        return new Binding(PASSAGE, FIELD_TEXT, passageId, owner);
    }

    static Binding place(UUID passageId, UUID owner) {
        return new Binding(PASSAGE, FIELD_PLACE, passageId, owner);
    }

    static Binding outline(UUID rowId, UUID owner) {
        return new Binding(OUTLINE, FIELD_OUTLINE, rowId, owner);
    }

    /** One entry of an outline: a section's heading, its level and the page it starts on. */
    record Entry(int level, String title, int page) {
        @Override
        public String toString() {
            return "Entry[level=" + level + ", page=" + page + "]";
        }
    }

    /** A passage's place: its section path, outermost first, and its boxes on its page or its range in its text. */
    record Place(List<String> sectionPath, List<List<Double>> boxes, List<Integer> range) {
        @Override
        public String toString() {
            return "Place[depth=" + sectionPath.size() + "]";
        }
    }

    static byte[] placeJson(Place place) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ArrayNode path = node.putArray("sectionPath");
        place.sectionPath().forEach(path::add);
        if (place.boxes() != null) {
            ArrayNode boxes = node.putArray("boxes");
            for (List<Double> box : place.boxes()) {
                ArrayNode values = boxes.addArray();
                box.forEach(values::add);
            }
        } else {
            ArrayNode range = node.putArray("range");
            place.range().forEach(range::add);
        }
        return JSON.writeValueAsBytes(node);
    }

    static Place place(byte[] json) {
        JsonNode node = JSON.readTree(json);
        List<String> path = new ArrayList<>();
        node.path("sectionPath").forEach(title -> path.add(title.asString()));
        List<List<Double>> boxes = null;
        List<Integer> range = null;
        if (node.has("boxes")) {
            boxes = new ArrayList<>();
            for (JsonNode box : node.path("boxes")) {
                List<Double> values = new ArrayList<>();
                box.forEach(value -> values.add(value.asDouble()));
                boxes.add(List.copyOf(values));
            }
            boxes = List.copyOf(boxes);
        } else {
            List<Integer> found = new ArrayList<>();
            node.path("range").forEach(value -> found.add(value.asInt()));
            range = List.copyOf(found);
        }
        return new Place(List.copyOf(path), boxes, range);
    }

    static byte[] outlineJson(List<Entry> outline) {
        ArrayNode node = JsonNodeFactory.instance.arrayNode();
        for (Entry entry : outline) {
            node.addObject().put("level", entry.level()).put("title", entry.title()).put("page", entry.page());
        }
        return JSON.writeValueAsBytes(node);
    }

    static List<Entry> outline(byte[] json) {
        List<Entry> entries = new ArrayList<>();
        for (JsonNode entry : JSON.readTree(json)) {
            entries.add(new Entry(entry.path("level").asInt(), entry.path("title").asString(),
                    entry.path("page").asInt()));
        }
        return List.copyOf(entries);
    }
}
