package io.github.gmshield.conversations.web;

import io.github.gmshield.conversations.service.ConversationProblems;
import io.github.gmshield.conversations.service.Conversations;
import io.github.gmshield.conversations.service.TurnStreams;
import io.github.gmshield.shared.service.OwnerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The GM's conversation (Story 2.4): the current conversation, a new turn, a turn's answer as server-sent events, and
 * the GM's stop of an answer (Story 2.6).
 * The owner is the session's account, set by the request edge ({@code accounts.web}); another owner's id, an unknown id
 * and a malformed id are the same {@code request.not_found} (404). Every answer is {@code no-store}.
 */
@RestController
@Tag(name = "conversations")
class ConversationController {

    static final String CURRENT = "/api/v1/conversations/current";
    static final String TURNS = "/api/v1/campaigns/{campaignId}/turns";
    static final String EVENTS = "/api/v1/turns/{turnId}/events";
    static final String STOP = "/api/v1/turns/{turnId}/stop";
    static final String LAST_EVENT_ID = "Last-Event-ID";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern SEQUENCE = Pattern.compile("\\d{1,18}");

    private final Conversations conversations;
    private final TurnStreams streams;

    ConversationController(Conversations conversations, TurnStreams streams) {
        this.conversations = conversations;
        this.streams = streams;
    }

    @Schema(name = "CurrentConversation", requiredProperties = {"campaignId", "hasSources"})
    record CurrentView(
            @Schema(description = "The current campaign's id", format = "uuid") UUID campaignId,
            @Schema(description = "Whether the campaign's scope has an accepted source (an allowed source, or one whose"
                    + " index change is still pending)") boolean hasSources) {
    }

    @Schema(name = "NewTurn", requiredProperties = "question")
    record NewTurn(
            @Schema(description = "The GM's question: 1 to 4,000 code points, not blank", minLength = 1,
                    maxLength = 4000) String question) {

        @Override
        public String toString() {
            return "NewTurn[]";
        }
    }

    @Schema(name = "Turn", requiredProperties = {"id", "conversationId", "language"})
    record TurnView(
            @Schema(description = "The turn's id", format = "uuid") UUID id,
            @Schema(description = "The campaign's conversation", format = "uuid") UUID conversationId,
            @Schema(description = "The question's language, null when unsure", types = {"string", "null"},
                    allowableValues = {"pt-BR", "en"}) String language) {
    }

    @Schema(name = "TurnDeltaEvent", requiredProperties = "text",
            description = "The data of a delta event: more of the answer's text, as Markdown")
    record DeltaEvent(@Schema(description = "Display text, to append") String text) {
    }

    @Schema(name = "TurnCitationEvent", requiredProperties = {"passageId", "offset"},
            description = "The data of a citation event: a passage cited at a place of the answer's text")
    record CitationEvent(
            @Schema(format = "uuid") UUID passageId,
            @Schema(description = "Where the citation stands, in code points of the answer's text", format = "int32",
                    minimum = "0") int offset) {
    }

    @Schema(name = "TurnSourceReference", requiredProperties = {"number", "materialName", "sectionPath", "page",
        "passageIds", "removed"}, description = "A source reference of a completed answer, stored by value with the"
            + " turn: one distinct place (material version, section path, page) the answer cited")
    record SourceReference(
            @Schema(description = "Its number, 1..n by first citation: unique within the turn and contiguous from 1",
                    format = "int32", minimum = "1") int number,
            @Schema(description = "The material's name") String materialName,
            @ArraySchema(arraySchema = @Schema(description = "The section path, outermost first; may be empty"),
                    schema = @Schema(type = "string")) List<String> sectionPath,
            @Schema(description = "The page", format = "int32", minimum = "1") int page,
            @ArraySchema(arraySchema = @Schema(description = "The cited passages at this place, in the order first"
                    + " cited; a passage id appears in at most one reference of the turn"), minItems = 1,
                    schema = @Schema(type = "string", format = "uuid"))
            List<UUID> passageIds,
            @Schema(description = "Whether its source has been removed since (its version gone, discarded or pending"
                    + " removal, or its material gone), as of when the event is sent. A version superseded by a newer"
                    + " accepted one is not removed") boolean removed) {
    }

    @Schema(name = "TurnDoneEvent", requiredProperties = {"stopped", "silent", "references"},
            description = "The data of a done event: the answer is complete, or the GM stopped it, with its source"
                    + " references. Built when the event is sent, so a replay shows removed as of then")
    record DoneEvent(
            @Schema(description = "True when the GM stopped the answer: the text sent so far is all there is, and the"
                    + " references are those of the citations sent so far") boolean stopped,
            @Schema(description = "True exactly when the answer was not stopped and references is empty: the sources"
                    + " say nothing about the question") boolean silent,
            @ArraySchema(arraySchema = @Schema(description = "The source references, by number (1..n, each once)"),
                    schema = @Schema(implementation = SourceReference.class)) List<SourceReference> references) {
    }

    @Schema(name = "TurnErrorEvent", requiredProperties = "code",
            description = "The data of an error event: the answer ended without completing")
    record ErrorEvent(@Schema(description = "category.reason, for example model.unavailable") String code) {
    }

    @GetMapping(path = CURRENT, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCurrentConversation", summary = "The current campaign's conversation: its campaign and"
            + " whether it has an accepted source; request.not_found (404) when the account has no campaign",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<CurrentView> current() {
        Conversations.Current current = conversations.current();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CurrentView(current.campaignId(), current.hasSources()));
    }

    @PostMapping(path = TURNS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "askQuestion", summary = "Ask a question in the campaign's conversation: the turn and its"
            + " answer job are recorded together (201); request.invalid (400) for a blank question or one past 4,000"
            + " code points, request.not_found (404) for a campaign that is not the account's; nothing is recorded then",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<TurnView> ask(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @RequestBody NewTurn body) {
        UUID campaign = parse(campaignId).orElseThrow(ConversationProblems::notFound);
        Conversations.Asked asked = conversations.ask(campaign, body == null ? null : body.question());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new TurnView(asked.turnId(), asked.conversationId(), asked.language()));
    }

    @GetMapping(path = EVENTS)
    @Operation(operationId = "streamTurnEvents", summary = "The turn's answer as server-sent events: the stored events"
            + " after Last-Event-ID (0 when absent), then new ones as they are written. Events: delta (TurnDeltaEvent),"
            + " citation (TurnCitationEvent), done (TurnDoneEvent) and error (TurnErrorEvent), each with id: its"
            + " sequence (1, 2, ... per turn). A comment line ': keep-alive' every 15 s; the stream closes after done or"
            + " error, and after 30 minutes without either: the client then reopens it with Last-Event-ID, the id of"
            + " the last event it received. Leaving never touches the answer. request.not_found (404) for a turn that"
            + " is not the account's",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "200", description = "The event stream",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(
                            description = "Each event's data is one of these, by its event name",
                            oneOf = {DeltaEvent.class, CitationEvent.class, DoneEvent.class, ErrorEvent.class}))))
    ResponseEntity<SseEmitter> events(
            @Parameter(description = "The turn's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("turnId") String turnId,
            @Parameter(in = ParameterIn.HEADER, name = LAST_EVENT_ID, description = "The id of the last event received:"
                    + " only later events are sent", schema = @Schema(type = "string"))
            @RequestHeader(name = LAST_EVENT_ID, required = false) String lastEventId) {
        UUID owner = OwnerContext.require();
        Conversations.Turn turn = parse(turnId).flatMap(conversations::turn)
                .orElseThrow(ConversationProblems::notFound);
        SseEmitter emitter = streams.open(owner, turn.id(), after(lastEventId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    @PostMapping(path = STOP)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "stopTurn", summary = "Stop the turn's answer (204): its stream ends at once with a done"
            + " event marked stopped, after the text and citations stored so far, and the worker is told to stop at its"
            + " next batch. A turn whose answer has ended already is left as it is (204 too), so repeating it is safe."
            + " request.not_found (404) for a turn that is not the account's",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<Void> stop(
            @Parameter(description = "The turn's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("turnId") String turnId) {
        conversations.stop(parse(turnId).orElseThrow(ConversationProblems::notFound));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** The sequence after which to send: the last event id received, 0 when absent or not one. */
    static long after(String lastEventId) {
        return lastEventId != null && SEQUENCE.matcher(lastEventId.strip()).matches()
                ? Long.parseLong(lastEventId.strip()) : 0;
    }

    /** A malformed id is no different from an unknown one. */
    private static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }
}
