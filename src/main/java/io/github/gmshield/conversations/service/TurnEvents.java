package io.github.gmshield.conversations.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * How a batch's events are stored (Story 2.4): one JSON array, encrypted as a whole, of {@code {"type": "delta",
 * "text"}}, {@code {"type": "citation", "passageId", "offset"}}, {@code {"type": "tier", "tier", "offset"}} (Story
 * 3.7), {@code {"type": "done"}} (with {@code "stopped": true} when the GM stopped the answer, Story 2.6) and
 * {@code {"type": "error", "code"}}, in order. Their sequences are the batch row's, so they are not stored here.
 */
final class TurnEvents {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TurnEvents() {
    }

    static byte[] encode(List<TurnEvent> events) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (TurnEvent event : events) {
            array.add(data(event).put("type", event.type()));
        }
        return JSON.writeValueAsBytes(array);
    }

    /** The events of a stored batch whose first event takes {@code firstSequence}. */
    static List<TurnEvent> decode(byte[] json, long firstSequence) {
        List<TurnEvent> events = new ArrayList<>();
        long sequence = firstSequence;
        for (JsonNode node : JSON.readTree(json)) {
            String passage = node.path("passageId").asString(null);
            events.add(new TurnEvent(sequence++, node.path("type").asString(), node.path("text").asString(null),
                    passage == null ? null : UUID.fromString(passage),
                    node.has("offset") ? node.path("offset").asInt() : null, node.path("code").asString(null),
                    node.path("stopped").asBoolean(false), node.path("tier").asString(null)));
        }
        return List.copyOf(events);
    }

    /**
     * The event's data as stored: {@code {text}}, {@code {passageId, offset}}, {@code {tier, offset}}, {@code {}} (or
     * {@code {stopped: true}}) or {@code {code}}. The stream sends a {@code done} event's data from {@link #doneData}
     * instead.
     */
    static ObjectNode data(TurnEvent event) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        switch (event.type()) {
            case TurnEvent.DELTA -> data.put("text", event.text());
            case TurnEvent.CITATION -> data.put("passageId", event.passageId().toString()).put("offset", event.offset());
            case TurnEvent.TIER -> data.put("tier", event.tier()).put("offset", event.offset());
            case TurnEvent.ERROR -> data.put("code", event.code());
            default -> {
                // done: only whether it was stopped; the stream builds its data from the references
                if (event.stopped()) {
                    data.put("stopped", true);
                }
            }
        }
        return data;
    }

    /** The event's data as one line of JSON. */
    static String dataLine(TurnEvent event) {
        return JSON.writeValueAsString(data(event));
    }

    /**
     * The data of a {@code done} event (Story 2.5), built when streamed from the stored references:
     * {@code {stopped, silent, references: [{number, materialName, sectionPath, page, passageIds, removed, tier?}]}},
     * {@code tier} (Story 3.7) only on a reference stored with one. {@code stopped} (Story 2.6) is whether the GM
     * stopped the answer; {@code silent} is true exactly when it was not stopped and there is no reference: a stopped
     * answer is never the silent one.
     */
    static ObjectNode doneData(List<TurnReference> references, boolean stopped) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("stopped", stopped);
        data.put("silent", !stopped && references.isEmpty());
        ArrayNode list = data.putArray("references");
        for (TurnReference reference : references) {
            ObjectNode entry = list.addObject();
            entry.put("number", reference.number());
            entry.put("materialName", reference.materialName());
            ArrayNode path = entry.putArray("sectionPath");
            reference.sectionPath().forEach(path::add);
            entry.put("page", reference.page());
            ArrayNode passages = entry.putArray("passageIds");
            reference.passageIds().forEach(id -> passages.add(id.toString()));
            entry.put("removed", reference.removed());
            if (reference.tier() != null) {
                entry.put("tier", reference.tier());
            }
        }
        return data;
    }

    /** {@link #doneData} as one line of JSON. */
    static String doneLine(List<TurnReference> references, boolean stopped) {
        return JSON.writeValueAsString(doneData(references, stopped));
    }
}
