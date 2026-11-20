package io.github.gmshield.conversations.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.conversations.service.ConversationProblems;
import io.github.gmshield.conversations.service.Conversations;
import io.github.gmshield.conversations.service.TurnStreams;
import io.github.gmshield.scope.service.SourcePage;
import io.github.gmshield.scope.service.SourcePicture;
import io.github.gmshield.shared.service.OwnerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
 * the GM's stop of an answer (Story 2.6), and from Story 2.7 a page of the version a stored source reference names, as
 * JSON with the same shape as {@code MaterialPageView}, and its picture, streamed as WebP with {@code nosniff}.
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
    static final String REFERENCE_PAGE = "/api/v1/turns/{turnId}/references/{number}/pages/{page}";
    static final String REFERENCE_PICTURE = "/api/v1/turns/{turnId}/references/{number}/pages/{page}/picture";
    static final String WEBP = "image/webp";
    /** A reference or page number as text: 1 to 9,999,999, never a sign or a leading zero; anything else is nowhere. */
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,6}");
    static final String LAST_EVENT_ID = "Last-Event-ID";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern SEQUENCE = Pattern.compile("\\d{1,18}");

    private final Conversations conversations;
    private final TurnStreams streams;

    ConversationController(Conversations conversations, TurnStreams streams) {
        this.conversations = conversations;
        this.streams = streams;
    }

    @Schema(name = "CurrentConversation", requiredProperties = {"campaignId", "hasSources", "indexing", "indexingFailed"})
    record CurrentView(
            @Schema(description = "The current campaign's id", format = "uuid") UUID campaignId,
            @Schema(description = "Whether the campaign's scope has an accepted source (an allowed source, or one whose"
                    + " index change is still pending)") boolean hasSources,
            @Schema(description = "How many passages of the campaign's scope are still being prepared for search (the"
                    + " distinct in-scope passages covered by an index change that has not ended); null when nothing in"
                    + " scope is pending", types = {"object", "null"}, implementation = IndexingView.class)
            IndexingView indexing,
            @Schema(description = "How many passages of the campaign's scope could not be prepared for search (the"
                    + " distinct in-scope passages covered by an index change whose job failed), and whether a retry"
                    + " remains (contract 1.16.0); null when none failed", types = {"object", "null"},
                    implementation = IndexingFailureView.class)
            IndexingFailureView indexingFailed) {

        static CurrentView of(Conversations.Current current) {
            return new CurrentView(current.campaignId(), current.hasSources(),
                    current.indexing() == null ? null : new IndexingView(current.indexing().remaining()),
                    current.indexingFailed() == null ? null : new IndexingFailureView(
                            current.indexingFailed().passages(), current.indexingFailed().retrying()));
        }
    }

    @Schema(name = "IndexingFailure", requiredProperties = {"passages", "retrying"},
            description = "Passages that could not be prepared for search")
    record IndexingFailureView(
            @Schema(description = "The passages of the campaign's scope whose index change failed", format = "int32",
                    minimum = "1", example = "200") int passages,
            @Schema(description = "True while a retry of one of their index changes remains (every 10 minutes, at most"
                    + " 3 times); false once none does") boolean retrying) {
    }

    @Schema(name = "IndexingCount", requiredProperties = {"remaining"},
            description = "Passages still being prepared for search")
    record IndexingView(
            @Schema(description = "The passages of the campaign's scope still waiting for search", format = "int32",
                    minimum = "1", example = "2000") int remaining) {
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

    /** A text line of a picture page, as {@code PageLine}. */
    record LineView(String text, List<Double> box) {
        @Override
        public String toString() {
            return "LineView[box=" + box + "]";
        }
    }

    /** A passage on the page, as {@code PagePassage}: its boxes, or its range in a text page's text. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PassageView(String id, List<List<Double>> boxes, List<Integer> range) {
    }

    /** A picture's size, as {@code PagePicture}. */
    record PictureView(int width, int height) {
    }

    /** The cited page: the same shape as {@code MaterialPageView}, so one view renders both. */
    record ReferencePageView(int page, int pageTotal, PictureView picture, List<LineView> lines, String text,
                             List<PassageView> passages, String outcome) {

        static ReferencePageView of(SourcePage page) {
            return new ReferencePageView(page.page(), page.pageTotal(), page.pictureWidth() == null
                    || page.pictureHeight() == null ? null : new PictureView(page.pictureWidth(), page.pictureHeight()),
                    page.lines().stream().map(line -> new LineView(line.text(), line.box())).toList(), page.text(),
                    page.passages().stream().map(place -> new PassageView(place.id().toString(), place.boxes(),
                            place.range())).toList(), page.outcome());
        }

        @Override
        public String toString() {
            return "ReferencePageView[page=" + page + ", pageTotal=" + pageTotal + "]";
        }
    }

    @GetMapping(path = CURRENT, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCurrentConversation", summary = "The current campaign's conversation: its campaign,"
            + " whether it has an accepted source, and how many of its passages are still being prepared for search"
            + " (null when nothing is pending); request.not_found (404) when the account has no campaign",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<CurrentView> current() {
        Conversations.Current current = conversations.current();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CurrentView.of(current));
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

    private static final String REFERENCE_SUMMARY = " The ground is the path: a source reference stored with a turn of"
            + " the account's campaign (AD-9). The page is read from the version the reference names, even when a newer"
            + " one was accepted since; any page from 1 to its page total. request.not_found (404) for a turn that is"
            + " not the account's, a malformed or unknown id or number, no such reference, a version removed since, a"
            + " page outside 1 to the page total, or a page with no view (unless it is unreadable)";

    @GetMapping(path = REFERENCE_PAGE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getReferencePage", summary = "One page of the version a turn's source reference names:"
            + " its picture size and text lines (a PDF page) or its text (a text page), and its passages' places, the"
            + " same shape as MaterialPageView; an unreadable page with no view has picture null and outcome"
            + " unreadable." + REFERENCE_SUMMARY,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "200", description = "The page",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(ref = "#/components/schemas/MaterialPageView"))))
    ResponseEntity<ReferencePageView> referencePage(
            @Parameter(description = "The turn's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("turnId") String turnId,
            @Parameter(description = "The reference's number, from 1", schema = @Schema(type = "integer",
                    format = "int32", minimum = "1")) @PathVariable("number") String number,
            @Parameter(description = "The page's number, from 1", schema = @Schema(type = "integer", format = "int32",
                    minimum = "1")) @PathVariable("page") String page) {
        SourcePage found = parse(turnId).flatMap(turn -> number(number).flatMap(reference -> number(page)
                        .flatMap(wanted -> conversations.referencePage(turn, reference, wanted))))
                .orElseThrow(ConversationProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReferencePageView.of(found));
    }

    // No `produces`: a refusal is problem JSON, which an image's Accept would otherwise turn into a 406.
    @GetMapping(path = REFERENCE_PICTURE)
    @Operation(operationId = "getReferencePagePicture", summary = "The picture of one page of the version a turn's"
            + " source reference names, as WebP, streamed. request.not_found (404) also for a page with no picture (a"
            + " text page, or an unreadable page with no view)." + REFERENCE_SUMMARY,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "200", description = "The page's picture",
                    content = @Content(mediaType = WEBP, schema = @Schema(type = "string", format = "binary"))))
    void referencePicture(
            @Parameter(description = "The turn's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("turnId") String turnId,
            @Parameter(description = "The reference's number, from 1", schema = @Schema(type = "integer",
                    format = "int32", minimum = "1")) @PathVariable("number") String number,
            @Parameter(description = "The page's number, from 1", schema = @Schema(type = "integer", format = "int32",
                    minimum = "1")) @PathVariable("page") String page,
            @Parameter(hidden = true) HttpServletResponse response) throws IOException {
        SourcePicture picture = parse(turnId).flatMap(turn -> number(number).flatMap(reference -> number(page)
                        .flatMap(wanted -> conversations.referencePicture(turn, reference, wanted))))
                .orElseThrow(ConversationProblems::notFound);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(WEBP);
        response.setContentLengthLong(picture.size());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (OutputStream out = response.getOutputStream()) {
            picture.writeTo(out);
        }
    }

    /** A malformed number is no different from an unknown one. */
    private static Optional<Integer> number(String text) {
        return text != null && NUMBER.matcher(text).matches() ? Optional.of(Integer.parseInt(text)) : Optional.empty();
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
