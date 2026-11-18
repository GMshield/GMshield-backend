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
 * How page views are stored (Story 1.19): the binding of their encrypted content, and its JSON: a picture page's text
 * lines with their boxes ({@code {"lines": [{"text", "box": [l, t, r, b]}, ...]}}, boxes as fractions of the picture
 * from its top-left corner), or a text page's text ({@code {"text": "..."}}).
 */
final class PageViews {

    static final String PAGE_VIEW = "page_view";
    static final String FIELD_CONTENT = "content";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PageViews() {
    }

    static Binding content(UUID pageViewId, UUID owner) {
        return new Binding(PAGE_VIEW, FIELD_CONTENT, pageViewId, owner);
    }

    /** A line of a picture page: its text and its box. */
    record Line(String text, List<Double> box) {
        @Override
        public String toString() {
            return "Line[box=" + box + "]";
        }
    }

    /** What a page shows besides its picture: its lines (a picture page), or its text (a text page). */
    record Content(List<Line> lines, String text) {
        @Override
        public String toString() {
            return "Content[lines=" + (lines == null ? 0 : lines.size()) + ", text="
                    + (text == null ? 0 : text.length()) + "]";
        }
    }

    static byte[] contentJson(Content content) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (content.text() != null) {
            node.put("text", content.text());
        } else {
            ArrayNode lines = node.putArray("lines");
            for (Line line : content.lines()) {
                ObjectNode item = lines.addObject();
                item.put("text", line.text());
                ArrayNode box = item.putArray("box");
                line.box().forEach(box::add);
            }
        }
        return JSON.writeValueAsBytes(node);
    }

    static Content content(byte[] json) {
        JsonNode node = JSON.readTree(json);
        if (node.has("text")) {
            return new Content(List.of(), node.path("text").asString());
        }
        List<Line> lines = new ArrayList<>();
        for (JsonNode line : node.path("lines")) {
            List<Double> box = new ArrayList<>(4);
            line.path("box").forEach(side -> box.add(side.asDouble()));
            lines.add(new Line(line.path("text").asString(), List.copyOf(box)));
        }
        return new Content(List.copyOf(lines), null);
    }
}
