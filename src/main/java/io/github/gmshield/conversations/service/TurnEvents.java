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
 * "text"}}, {@code {"type": "citation", "passageId", "offset"}} (or, Story 4.3 part C, {@code "recordId"} in place of
 * {@code "passageId"}), {@code {"type": "tier", "tier", "offset"}} (Story
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
            String record = node.path("recordId").asString(null);
            events.add(new TurnEvent(sequence++, node.path("type").asString(), node.path("text").asString(null),
                    passage == null ? null : UUID.fromString(passage), record == null ? null : UUID.fromString(record),
                    node.has("offset") ? node.path("offset").asInt() : null, node.path("code").asString(null),
                    node.path("stopped").asBoolean(false), node.path("tier").asString(null)));
        }
        return List.copyOf(events);
    }

    /**
     * The event's data as stored: {@code {text}}, {@code {passageId, offset}} or {@code {recordId, offset}},
     * {@code {tier, offset}}, {@code {}} (or {@code {stopped: true}}) or {@code {code}}. The stream sends a
     * {@code done} event's data from {@link #doneData} instead.
     */
    static ObjectNode data(TurnEvent event) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        switch (event.type()) {
            case TurnEvent.DELTA -> data.put("text", event.text());
            case TurnEvent.CITATION -> (event.recordId() != null ? data.put("recordId", event.recordId().toString())
                    : data.put("passageId", event.passageId().toString())).put("offset", event.offset());
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
     * The data of a {@code done} event (Story 2.5), built when streamed from the turn's {@link Conversations.Ending}:
     * {@code {stopped, silent, modelKnowledge, references: [{number, kind, materialName, sectionPath, page, passageIds,
     * removed, tier?}]}}, {@code tier} (Story 3.7) only on a reference stored with one. {@code kind} (Story 4.3 part C)
     * is {@code passage} or {@code record}; a record's reference adds {@code recordId}, {@code recordType},
     * {@code name} and {@code fields: [{name, value}]}. {@code stopped} (Story 2.6) is whether the GM stopped the
     * answer; {@code silent} is true exactly when it was not stopped, there is no reference and (Story 3.8) no
     * {@code model} tier event was stored: a stopped answer is never the silent one. {@code modelKnowledge} is the
     * turn's frozen switch.
     */
    static ObjectNode doneData(Conversations.Ending ending, boolean stopped) {
        List<TurnReference> references = ending.references();
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("stopped", stopped);
        data.put("silent", !stopped && references.isEmpty() && !ending.modelTier());
        data.put("modelKnowledge", ending.modelKnowledge());
        ArrayNode list = data.putArray("references");
        for (TurnReference reference : references) {
            ObjectNode entry = list.addObject();
            entry.put("number", reference.number());
            entry.put("kind", reference.kind());
            TurnReference.Cited record = reference.record();
            if (record != null) {
                entry.put("recordId", record.id().toString());
                entry.put("recordType", record.type());
                entry.put("name", record.name());
                ArrayNode fields = entry.putArray("fields");
                record.fields().forEach(field -> fields.addObject().put("name", field.name())
                        .put("value", field.value()));
            }
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
    static String doneLine(Conversations.Ending ending, boolean stopped) {
        return JSON.writeValueAsString(doneData(ending, stopped));
    }
}
