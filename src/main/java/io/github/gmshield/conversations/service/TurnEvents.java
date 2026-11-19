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
 * "text"}}, {@code {"type": "citation", "passageId", "offset"}}, {@code {"type": "done"}} and {@code {"type": "error",
 * "code"}}, in order. Their sequences are the batch row's, so they are not stored here.
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
                    node.has("offset") ? node.path("offset").asInt() : null, node.path("code").asString(null)));
        }
        return List.copyOf(events);
    }

    /** The event's data as the stream sends it: {@code {text}}, {@code {passageId, offset}}, {@code {}} or {@code {code}}. */
    static ObjectNode data(TurnEvent event) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        switch (event.type()) {
            case TurnEvent.DELTA -> data.put("text", event.text());
            case TurnEvent.CITATION -> data.put("passageId", event.passageId().toString()).put("offset", event.offset());
            case TurnEvent.ERROR -> data.put("code", event.code());
            default -> {
                // done: no data
            }
        }
        return data;
    }

    /** The event's data as one line of JSON. */
    static String dataLine(TurnEvent event) {
        return JSON.writeValueAsString(data(event));
    }
}
