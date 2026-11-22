package io.github.gmshield.conversations.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.conversations.service.Area;
import io.github.gmshield.conversations.service.ConversationProblems;
import io.github.gmshield.conversations.service.Conversations;
import io.github.gmshield.conversations.service.Searches;
import io.github.gmshield.conversations.service.TurnStreams;
import io.github.gmshield.scope.service.SourcePage;
import io.github.gmshield.scope.service.SourcePicture;
import io.github.gmshield.scope.service.Tier;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The GM's conversation (Story 2.4): the current conversation, a new turn, a turn's answer as server-sent events, and
 * the GM's stop of an answer (Story 2.6), and from Story 2.7 a page of the version a stored source reference names, as
 * JSON with the same shape as {@code MaterialPageView}, and its picture, streamed as WebP with {@code nosniff}. From
 * Story 3.2, a campaign's conversation with its question count. From Story 3.5, a page of a version on the ground "the
 * current scope" (an allowed source of the campaign's current stored scope), and its picture, in the same shapes.
 * From Story 3.9, a search of the library: created as a job (202) and read by id, with its results re-checked against
 * the campaign's current scope each time. From Story 3.10, conversations by area: a campaign's conversations of one area
 * (paged by offset, the most recently asked first), one conversation, its stored turns with their events, its rename on
 * its version, and a question that continues a conversation or starts one in an area. From Story 3.10 part B, what goes
 * with a conversation (its question count) and its deletion (202, no body): from then on it and its turns are
 * {@code request.not_found} on every route. From Story 3.11, the GM's dismissal of an ended turn (PUT) and its undo
 * (DELETE) on {@code /turns/{turnId}/dismissal}: a dismissed turn is left out of its conversation's turns, its question
 * counts and the history sent with a later question.
 * The owner is the session's account, set by the request edge ({@code accounts.web}); another owner's id, an unknown id
 * and a malformed id are the same {@code request.not_found} (404). Every answer is {@code no-store}.
 */
@RestController
@Tag(name = "conversations")
class ConversationController {

    static final String CURRENT = "/api/v1/conversations/current";
    static final String TURNS = "/api/v1/campaigns/{campaignId}/turns";
    static final String OF_CAMPAIGN = "/api/v1/campaigns/{campaignId}/conversation";
    static final String EVENTS = "/api/v1/turns/{turnId}/events";
    static final String STOP = "/api/v1/turns/{turnId}/stop";
    static final String DISMISSAL = "/api/v1/turns/{turnId}/dismissal";
    static final String REFERENCE_PAGE = "/api/v1/turns/{turnId}/references/{number}/pages/{page}";
    static final String REFERENCE_PICTURE = "/api/v1/turns/{turnId}/references/{number}/pages/{page}/picture";
    static final String SOURCE_PAGE = "/api/v1/campaigns/{campaignId}/sources/{versionId}/pages/{page}";
    static final String SOURCE_PICTURE = "/api/v1/campaigns/{campaignId}/sources/{versionId}/pages/{page}/picture";
    static final String SEARCHES = "/api/v1/campaigns/{campaignId}/searches";
    static final String SEARCH = "/api/v1/searches/{searchId}";
    static final String CONVERSATIONS = "/api/v1/campaigns/{campaignId}/conversations";
    static final String CONVERSATION = "/api/v1/conversations/{conversationId}";
    static final String CONVERSATION_TURNS = "/api/v1/conversations/{conversationId}/turns";
    static final String CONVERSATION_DELETION = "/api/v1/conversations/{conversationId}/deletion";
    static final String WEBP = "image/webp";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]?");
    /** A page's position: 0, or up to six digits with no leading zero. */
    private static final Pattern OFFSET_TEXT = Pattern.compile("0|[1-9][0-9]{0,5}");
    /** A reference or page number as text: 1 to 9,999,999, never a sign or a leading zero; anything else is nowhere. */
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,6}");
    static final String LAST_EVENT_ID = "Last-Event-ID";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern SEQUENCE = Pattern.compile("\\d{1,18}");

    private final Conversations conversations;
    private final Searches searches;
    private final TurnStreams streams;

    ConversationController(Conversations conversations, Searches searches, TurnStreams streams) {
        this.conversations = conversations;
        this.searches = searches;
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

    @Schema(name = "CampaignConversation", requiredProperties = {"id", "questions"},
            description = "A campaign's conversation (contract 1.19.0)")
    record OfCampaignView(
            @Schema(description = "The conversation's id", format = "uuid") UUID id,
            @Schema(description = "How many questions it shows (from contract 1.31.0 a dismissed one is not counted)",
                    format = "int64", minimum = "0", example = "3")
            long questions) {
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
                    maxLength = 4000) String question,
            @Schema(description = "The conversation to continue (contract 1.29.0): one of this campaign's. Absent or"
                    + " null: a new conversation of area, or without area the campaign's latest questions conversation",
                    types = {"string", "null"}, format = "uuid") String conversationId,
            @Schema(description = "The area of a new conversation (contract 1.29.0); with conversationId it must be"
                    + " that conversation's. Absent or null with no conversationId: the campaign's latest questions"
                    + " conversation is continued (made when it has none)", types = {"string", "null"},
                    allowableValues = {"questions", "rules"}) String area) {

        @Override
        public String toString() {
            return "NewTurn[conversationId=" + conversationId + ", area=" + area + "]";
        }
    }

    @Schema(name = "ConversationSummary", requiredProperties = {"id", "area", "name", "questions", "createdAt",
        "lastAskedAt", "version"}, description = "A conversation in a list (contract 1.29.0)")
    record SummaryView(
            @Schema(description = "The conversation's id", format = "uuid") UUID id,
            @Schema(allowableValues = {"questions", "rules"}) String area,
            @Schema(description = "The GM's name for it, or else its first question's first 60 code points, cut at a"
                    + " word boundary") String name,
            @Schema(description = "How many questions it shows: from contract 1.31.0 a dismissed one is not counted, so"
                    + " it may be 0", format = "int64", minimum = "0", example = "3")
            long questions,
            @Schema(description = "When its first question was asked", format = "date-time") String createdAt,
            @Schema(description = "When its newest question was asked", format = "date-time") String lastAskedAt,
            @Schema(description = "Its version: a rename names it and raises it by one", format = "int64",
                    minimum = "0") long version) {

        static SummaryView of(Conversations.Summary summary) {
            return new SummaryView(summary.id(), summary.area().word(), summary.name(), summary.questions(),
                    summary.createdAt().toString(), summary.lastAskedAt().toString(), summary.version());
        }

        @Override
        public String toString() {
            return "SummaryView[id=" + id + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ConversationPage", requiredProperties = {"items", "total"},
            description = "One page of a campaign's conversations of one area (contract 1.29.0)")
    record PageView(
            @ArraySchema(arraySchema = @Schema(description = "The most recently asked first"),
                    schema = @Schema(implementation = SummaryView.class)) List<SummaryView> items,
            @Schema(description = "The offset of the next page, as text; absent on the last", example = "20")
            String nextCursor,
            @Schema(description = "How many conversations the campaign has in this area", format = "int64",
                    example = "27") long total) {
    }

    @Schema(name = "Conversation", requiredProperties = {"id", "campaignId", "area", "name", "version"},
            description = "One conversation (contract 1.29.0)")
    record ConversationView(
            @Schema(format = "uuid") UUID id,
            @Schema(format = "uuid") UUID campaignId,
            @Schema(allowableValues = {"questions", "rules"}) String area,
            @Schema(description = "The GM's name for it, or else its first question's first 60 code points, cut at a"
                    + " word boundary") String name,
            @Schema(description = "Its version: a rename names it and raises it by one", format = "int64",
                    minimum = "0") long version) {

        static ConversationView of(Conversations.Opened opened) {
            return new ConversationView(opened.id(), opened.campaignId(), opened.area().word(), opened.name(),
                    opened.version());
        }

        @Override
        public String toString() {
            return "ConversationView[id=" + id + ", version=" + version + "]";
        }
    }

    @Schema(name = "ConversationDeletion", requiredProperties = "questions",
            description = "What goes with a conversation if it is deleted (contract 1.30.0)")
    record ConversationDeletionView(
            @Schema(description = "How many questions it shows (from contract 1.31.0 a dismissed one is not counted,"
                    + " though it goes too): each goes with its answer, events and source references", format = "int64",
                    minimum = "0", example = "3") long questions) {
    }

    @Schema(name = "ConversationChange", requiredProperties = {"version", "name"},
            description = "A rename of a conversation (contract 1.29.0)")
    record ConversationChangeBody(
            @Schema(description = "The version the rename was made on, as the conversation was read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "The new name, 1 to 120 code points after trimming", minLength = 1, maxLength = 120,
                    example = "Viagem ao norte") String name) {

        @Override
        public String toString() {
            return "ConversationChangeBody[version=" + version + "]";
        }
    }

    @Schema(name = "StoredTurnEvent", requiredProperties = {"sequence", "type", "data"},
            description = "A stored event of a turn, as its stream sends it (contract 1.29.0)")
    record StoredEventView(
            @Schema(description = "Its sequence: the stream's event id", format = "int64", minimum = "1") long sequence,
            @Schema(description = "The stream's event name", allowableValues = {"delta", "citation", "tier", "done",
                "error"}) String type,
            @Schema(description = "The event's data, by its type, as the stream sends it; a done event's built when"
                    + " read", oneOf = {DeltaEvent.class, CitationEvent.class, TierEvent.class, DoneEvent.class,
                        ErrorEvent.class}) Map<String, Object> data) {

        @Override
        public String toString() {
            return "StoredEventView[sequence=" + sequence + ", type=" + type + "]";
        }
    }

    @Schema(name = "StoredTurn", requiredProperties = {"id", "question", "language", "createdAt", "events"},
            description = "A turn of a conversation with its stored events (contract 1.29.0). A turn whose answer is"
                    + " still being written has no done or error event yet: its stream continues after the last"
                    + " sequence")
    record StoredTurnView(
            @Schema(format = "uuid") UUID id,
            @Schema(description = "The GM's question") String question,
            @Schema(description = "The question's language, null when unsure", types = {"string", "null"},
                    allowableValues = {"pt-BR", "en"}) String language,
            @Schema(format = "date-time") String createdAt,
            @ArraySchema(arraySchema = @Schema(description = "Its stored events, by sequence"),
                    schema = @Schema(implementation = StoredEventView.class)) List<StoredEventView> events) {

        static StoredTurnView of(Conversations.StoredTurn turn) {
            return new StoredTurnView(turn.id(), turn.question(), turn.language(), turn.createdAt().toString(),
                    turn.events().stream().map(event -> new StoredEventView(event.sequence(), event.type(),
                            JSON.convertValue(event.data(), OBJECT))).toList());
        }

        @Override
        public String toString() {
            return "StoredTurnView[id=" + id + "]";
        }
    }

    @Schema(name = "ConversationTurns", requiredProperties = "items",
            description = "A conversation's turns (contract 1.29.0)")
    record TurnsView(
            @ArraySchema(arraySchema = @Schema(description = "Its newest 200 turns, oldest first"),
                    schema = @Schema(implementation = StoredTurnView.class)) List<StoredTurnView> items) {
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

    @Schema(name = "TurnTierEvent", requiredProperties = {"tier", "offset"},
            description = "The data of a tier event (Story 3.7): the sources disagree, and the version of this tier of"
                    + " the scope starts at a place of the answer's text. The campaign's version comes first; each"
                    + " other tier's follows as an alternative. Only a tier with a source in the turn's scope. From"
                    + " contract 1.27.0 the tier model (Story 3.8) starts a part from the model's own knowledge, which"
                    + " has no reference; only on a turn whose model knowledge was on")
    record TierEvent(
            @Schema(allowableValues = {"campaign", "system", "other", "model"}) String tier,
            @Schema(description = "Where the tier's version starts, in code points of the answer's text",
                    format = "int32", minimum = "0") int offset) {
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
                    + " accepted one is not removed") boolean removed,
            @Schema(description = "The tier its source had in the turn's scope when the answer was written (Story"
                    + " 3.7), kept when the source leaves the scope; absent for a reference stored before then",
                    allowableValues = {"campaign", "system", "other"}) String tier) {
    }

    @Schema(name = "TurnDoneEvent", requiredProperties = {"stopped", "silent", "modelKnowledge", "references"},
            description = "The data of a done event: the answer is complete, or the GM stopped it, with its source"
                    + " references. Built when the event is sent, so a replay shows removed as of then")
    record DoneEvent(
            @Schema(description = "True when the GM stopped the answer: the text sent so far is all there is, and the"
                    + " references are those of the citations sent so far") boolean stopped,
            @Schema(description = "True exactly when the answer was not stopped, references is empty and the turn"
                    + " has no model tier event: the sources say nothing about the question") boolean silent,
            @Schema(description = "Whether the model could answer from its own knowledge on this turn: the switch as"
                    + " frozen when the question was asked (Story 3.8)") boolean modelKnowledge,
            @ArraySchema(arraySchema = @Schema(description = "The source references, by number (1..n, each once)"),
                    schema = @Schema(implementation = SourceReference.class)) List<SourceReference> references) {
    }

    @Schema(name = "TurnErrorEvent", requiredProperties = "code",
            description = "The data of an error event: the answer ended without completing")
    record ErrorEvent(@Schema(description = "category.reason, for example model.unavailable") String code) {
    }

    @Schema(name = "NewSearch", requiredProperties = "query", description = "A search of the campaign's scope (contract"
            + " 1.28.0). The filters only narrow the scope, never widen it")
    record NewSearch(
            @Schema(description = "What to find, by meaning or exact words: 1 to 500 code points, not blank",
                    minLength = 1, maxLength = 500) String query,
            @Schema(description = "Only this tier's sources; absent or null for every tier on",
                    types = {"string", "null"}, allowableValues = {"campaign", "system", "other"}) String tier,
            @Schema(description = "Only this material's version; absent or null for every material on",
                    types = {"string", "null"}, format = "uuid") String materialId) {

        @Override
        public String toString() {
            return "NewSearch[tier=" + tier + "]";
        }
    }

    @Schema(name = "SearchCreated", requiredProperties = "id")
    record SearchCreated(@Schema(description = "The search's id", format = "uuid") UUID id) {
    }

    @Schema(name = "SearchResult", requiredProperties = {"passageId", "versionId", "materialName", "sectionPath", "page",
        "firstWords", "tier"}, description = "A passage found, as it stands in the campaign's current scope")
    record SearchResultView(
            @Schema(format = "uuid") UUID passageId,
            @Schema(description = "Its material version: the source page opens on the ground of the current scope",
                    format = "uuid") UUID versionId,
            @Schema(description = "The material's name") String materialName,
            @ArraySchema(arraySchema = @Schema(description = "The section path, outermost first; may be empty"),
                    schema = @Schema(type = "string")) List<String> sectionPath,
            @Schema(description = "The page", format = "int32", minimum = "1") int page,
            @Schema(description = "The passage's first 160 code points (its white space made single spaces), cut at a"
                    + " word boundary") String firstWords,
            @Schema(description = "The tier of its source in the campaign's current scope",
                    allowableValues = {"campaign", "system", "other"}) String tier) {
    }

    @Schema(name = "Search", requiredProperties = {"id", "campaignId", "query", "tier", "materialId", "state",
        "results"}, description = "A search of the library (contract 1.28.0)")
    record SearchView(
            @Schema(format = "uuid") UUID id,
            @Schema(format = "uuid") UUID campaignId,
            @Schema(description = "The query as the GM wrote it") String query,
            @Schema(description = "The tier filter, null for none", types = {"string", "null"},
                    allowableValues = {"campaign", "system", "other"}) String tier,
            @Schema(description = "The material filter, null for none", types = {"string", "null"}, format = "uuid")
            UUID materialId,
            @Schema(description = "pending until its job ends: done with its results, or failed with a code",
                    allowableValues = {"pending", "done", "failed"}) String state,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Why it failed (category.reason, for example scope.passage_refused); only when"
                    + " failed") String code,
            @ArraySchema(arraySchema = @Schema(description = "Up to 20 results, best first; empty unless done. Each is"
                    + " re-checked against the campaign's current scope when read: one that left it since is not"
                    + " listed"), maxItems = 20, schema = @Schema(implementation = SearchResultView.class))
            List<SearchResultView> results) {

        static SearchView of(Searches.Found found) {
            return new SearchView(found.id(), found.campaignId(), found.query(),
                    found.tier() == null ? null : found.tier().word(), found.materialId(), found.state(), found.code(),
                    found.results().stream().map(result -> new SearchResultView(result.passageId(), result.versionId(),
                            result.materialName(), result.sectionPath(), result.page(), result.firstWords(),
                            result.tier().word())).toList());
        }

        @Override
        public String toString() {
            return "SearchView[id=" + id + ", state=" + state + ", results=" + results.size() + "]";
        }
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

    @GetMapping(path = OF_CAMPAIGN, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCampaignConversation", summary = "The campaign's latest questions conversation (from"
            + " contract 1.29.0 a campaign has many: the one a question with no conversation continues) and how many"
            + " questions it holds, for an archived campaign too; request.not_found (404) when the campaign has none or"
            + " is not the account's (unknown, another account's or malformed)",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<OfCampaignView> ofCampaign(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId) {
        Conversations.OfCampaign found = parse(campaignId).flatMap(conversations::ofCampaign)
                .orElseThrow(ConversationProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new OfCampaignView(found.id(),
                found.questions()));
    }

    @PostMapping(path = TURNS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "askQuestion", summary = "Ask a question in one of the campaign's conversations: the turn"
            + " and its answer job are recorded together (201). From contract 1.29.0: with conversationId, in that"
            + " conversation; with area only, in a new conversation of that area (a conversation is made by its first"
            + " question); with neither, in the campaign's latest questions conversation. The conversation's newest"
            + " question becomes this one. request.invalid (400) for a blank question or one past 4,000 code points, an"
            + " area that is not one, or an area other than the conversation's; request.not_found (404) for a campaign"
            + " that is not the account's or a conversationId that is not one of its conversations; nothing is"
            + " recorded then",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<TurnView> ask(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @RequestBody NewTurn body) {
        UUID campaign = parse(campaignId).orElseThrow(ConversationProblems::notFound);
        if (body == null) {
            throw ConversationProblems.invalid();
        }
        Area area = body.area() == null ? null : Area.of(body.area()).orElseThrow(ConversationProblems::invalid);
        UUID conversation = body.conversationId() == null ? null
                : parse(body.conversationId()).orElseThrow(ConversationProblems::notFound);
        Conversations.Asked asked = conversations.ask(campaign, body.question(), conversation, area);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new TurnView(asked.turnId(), asked.conversationId(), asked.language()));
    }

    @GetMapping(path = CONVERSATIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listConversations", summary = "The campaign's conversations of one area (contract 1.29.0),"
            + " the most recently asked first, each with its name and question count (from contract 1.31.0 dismissed"
            + " questions are not counted). A page starts at an offset and names the total of the list. A conversation"
            + " is made by its first question, so none is empty, though all its questions may be dismissed."
            + " request.invalid (400) without an area or for one that is not one, a limit outside 1 to 50, or an offset"
            + " outside 0 to 100000; request.not_found (404) for a campaign that is not the account's (unknown, another"
            + " account's or malformed)",
            parameters = {
                @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "area", in = ParameterIn.QUERY, required = true, description = "The area listed",
                        schema = @Schema(type = "string", allowableValues = {"questions", "rules"})),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many of the most recently"
                        + " asked to skip, 0 to 100000", schema = @Schema(type = "integer", format = "int32",
                                minimum = "0", maximum = "100000", defaultValue = "0"))})
    ResponseEntity<PageView> list(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(hidden = true) @RequestParam(value = "area", required = false) String area,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        Area listed = Area.of(area).orElseThrow(ConversationProblems::invalid);
        int size = limit(limit);
        int from = offset(offset);
        Conversations.Page page = parse(campaignId).flatMap(campaign -> conversations.list(campaign, listed, from, size))
                .orElseThrow(ConversationProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageView(
                page.items().stream().map(SummaryView::of).toList(),
                page.nextOffset() == null ? null : page.nextOffset().toString(), page.total()));
    }

    @GetMapping(path = CONVERSATION, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getConversation", summary = "One of the account's conversations (contract 1.29.0): its"
            + " campaign, area, name and version; request.not_found (404) for any other id (unknown, another account's,"
            + " malformed, of a campaign being deleted, or from contract 1.30.0 a conversation being deleted)",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<ConversationView> conversation(
            @Parameter(description = "The conversation's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("conversationId") String conversationId) {
        Conversations.Opened opened = parse(conversationId).flatMap(conversations::conversation)
                .orElseThrow(ConversationProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ConversationView.of(opened));
    }

    @GetMapping(path = CONVERSATION_TURNS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listConversationTurns", summary = "A conversation's turns (contract 1.29.0): its newest"
            + " 200, oldest first, each with its question, language and stored events as its stream sends them (a"
            + " done event's data built when read, so removed is as of then). A turn still being answered has no done"
            + " or error event yet; its stream continues after its last sequence. From contract 1.31.0 a dismissed turn"
            + " is left out (the 200 are the newest shown ones). request.not_found (404) for a conversation that is not"
            + " the account's",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<TurnsView> turns(
            @Parameter(description = "The conversation's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("conversationId") String conversationId) {
        List<Conversations.StoredTurn> turns = parse(conversationId).flatMap(conversations::turns)
                .orElseThrow(ConversationProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new TurnsView(turns.stream().map(StoredTurnView::of).toList()));
    }

    @PatchMapping(path = CONVERSATION, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "renameConversation", summary = "Renames one of the account's conversations on the version"
            + " it names (contract 1.29.0): the conversation as stored, its version raised by one."
            + " request.conversation_name_invalid (400) for a name outside 1 to 120 code points after trimming (params"
            + " min and max), request.stale_version (409) when the conversation has left that version, request.invalid"
            + " (400) without a version or for a malformed body, request.not_found (404) for any other id; nothing"
            + " changes then",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<ConversationView> rename(
            @Parameter(description = "The conversation's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("conversationId") String conversationId,
            @RequestBody ConversationChangeBody body) {
        UUID id = parse(conversationId).orElseThrow(ConversationProblems::notFound);
        if (body == null || body.version() == null || body.version() < 0) {
            throw ConversationProblems.invalid();
        }
        Conversations.Opened renamed = conversations.rename(id, body.version(), body.name())
                .orElseThrow(ConversationProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ConversationView.of(renamed));
    }

    @GetMapping(path = CONVERSATION_DELETION, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getConversationDeletion", summary = "What goes with one of the account's conversations if"
            + " it is deleted (contract 1.30.0): how many questions it shows, each with its answer (from contract"
            + " 1.31.0 a dismissed one is not counted, though it is deleted too). request.not_found (404) for any other"
            + " id (unknown, another account's, malformed, or of a conversation or campaign being"
            + " deleted)",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<ConversationDeletionView> conversationDeletion(
            @Parameter(description = "The conversation's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("conversationId") String conversationId) {
        long questions = parse(conversationId).flatMap(conversations::deletion)
                .orElseThrow(ConversationProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ConversationDeletionView(questions));
    }

    @DeleteMapping(path = CONVERSATION)
    @Operation(operationId = "deleteConversation", summary = "Deletes one of the account's conversations (contract"
            + " 1.30.0; 202, no body): from now on it is not found on every route (its campaign's list and counts, the"
            + " conversation, its turns, a question naming it, the campaign's latest questions conversation, and its"
            + " turns' events, stop and reference pages), its answers still being written are cancelled, and its turns,"
            + " their events and source references are removed in the background. Nothing of its campaign, of the other"
            + " conversations, of the searches or of the materials changes. Cannot be undone. request.not_found (404)"
            + " for any other id, a conversation already being deleted included",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "202", description = "The deletion started"))
    ResponseEntity<Void> deleteConversation(
            @Parameter(description = "The conversation's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("conversationId") String conversationId) {
        if (!parse(conversationId).map(conversations::delete).orElse(false)) {
            throw ConversationProblems.notFound();
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping(path = EVENTS)
    @Operation(operationId = "streamTurnEvents", summary = "The turn's answer as server-sent events: the stored events"
            + " after Last-Event-ID (0 when absent), then new ones as they are written. Events: delta (TurnDeltaEvent),"
            + " citation (TurnCitationEvent), done (TurnDoneEvent) and error (TurnErrorEvent), each with id: its"
            + " sequence (1, 2, ... per turn). From contract 1.26.0 also tier (TurnTierEvent), stored and replayed like"
            + " citation. A comment line ': keep-alive' every 15 s; the stream closes after done or"
            + " error, and after 30 minutes without either: the client then reopens it with Last-Event-ID, the id of"
            + " the last event it received. Leaving never touches the answer. request.not_found (404) for a turn that"
            + " is not the account's, or (contract 1.30.0) of a conversation being deleted",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "200", description = "The event stream",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(
                            description = "Each event's data is one of these, by its event name",
                            oneOf = {DeltaEvent.class, CitationEvent.class, TierEvent.class, DoneEvent.class,
                                ErrorEvent.class}))))
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
            + " request.not_found (404) for a turn that is not the account's, or (contract 1.30.0) of a conversation"
            + " being deleted",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<Void> stop(
            @Parameter(description = "The turn's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("turnId") String turnId) {
        conversations.stop(parse(turnId).orElseThrow(ConversationProblems::notFound));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static final String DISMISSAL_NOT_FOUND = " request.not_found (404) for a turn that is not the account's"
            + " (unknown, another account's or malformed) or of a conversation being deleted; nothing changes then."
            + " Neither the conversation's version nor its newest question's time moves";

    @PutMapping(path = DISMISSAL)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "dismissTurn", summary = "Dismiss the turn's answer (contract 1.31.0; 204): from now on the"
            + " turn, with its question and answer, is left out of its conversation's turns and question counts and is"
            + " never sent as history to a later question; its own events, references and reference pages still"
            + " answer, and the conversation's default name stays its first question's. Only an ended turn (stopped,"
            + " or with a done or error event) is dismissed: conversation.turn_running (409) otherwise, and nothing"
            + " changes. A turn dismissed already stays as it is (204 too), so repeating it is safe."
            + DISMISSAL_NOT_FOUND,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "204", description = "The turn is dismissed"))
    ResponseEntity<Void> dismiss(
            @Parameter(description = "The turn's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("turnId") String turnId) {
        conversations.dismiss(parse(turnId).orElseThrow(ConversationProblems::notFound));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping(path = DISMISSAL)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "restoreTurn", summary = "Undo the dismissal of the turn's answer (contract 1.31.0; 204):"
            + " the turn is shown again where it was, listed with its conversation's turns, counted, and sent as"
            + " history again. A turn that is shown already stays as it is (204 too), so repeating it is safe."
            + DISMISSAL_NOT_FOUND,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "204", description = "The turn is shown"))
    ResponseEntity<Void> restore(
            @Parameter(description = "The turn's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("turnId") String turnId) {
        conversations.restore(parse(turnId).orElseThrow(ConversationProblems::notFound));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static final String REFERENCE_SUMMARY = " The ground is the path: a source reference stored with a turn of"
            + " the account's campaign (AD-9). The page is read from the version the reference names, even when a newer"
            + " one was accepted since; any page from 1 to its page total. request.not_found (404) for a turn that is"
            + " not the account's (from contract 1.30.0, one of a conversation being deleted too), a malformed or"
            + " unknown id or number, no such reference, a version removed since, a page outside 1 to the page total,"
            + " or a page with no view (unless it is unreadable)";

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
        writePicture(picture, response);
    }

    private static void writePicture(SourcePicture picture, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(WEBP);
        response.setContentLengthLong(picture.size());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (OutputStream out = response.getOutputStream()) {
            picture.writeTo(out);
        }
    }

    private static final String SOURCE_SUMMARY = " The ground is the path: the campaign's current scope (Story 3.5), as"
            + " its stored settings resolve it now; the version must be an allowed source of it (the ready version of a"
            + " material switched on in a tier switched on). Any page from 1 to its page total. request.not_found (404)"
            + " for a campaign that is not the account's, a malformed id or number, a version outside that scope"
            + " (another account's, unknown, not ready, or switched off: none is told apart), a page outside 1 to the"
            + " page total, or a page with no view (unless it is unreadable)";

    @GetMapping(path = SOURCE_PAGE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getScopeSourcePage", summary = "One page of a version in the campaign's current scope:"
            + " the same shape as MaterialPageView, for a search result." + SOURCE_SUMMARY,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "200", description = "The page",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(ref = "#/components/schemas/MaterialPageView"))))
    ResponseEntity<ReferencePageView> sourcePage(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The material version's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("versionId") String versionId,
            @Parameter(description = "The page's number, from 1", schema = @Schema(type = "integer", format = "int32",
                    minimum = "1")) @PathVariable("page") String page) {
        SourcePage found = parse(campaignId).flatMap(campaign -> parse(versionId).flatMap(version -> number(page)
                        .flatMap(wanted -> conversations.sourcePage(campaign, version, wanted))))
                .orElseThrow(ConversationProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReferencePageView.of(found));
    }

    // No `produces`: a refusal is problem JSON, which an image's Accept would otherwise turn into a 406.
    @GetMapping(path = SOURCE_PICTURE)
    @Operation(operationId = "getScopeSourcePagePicture", summary = "The picture of one page of a version in the"
            + " campaign's current scope, as WebP, streamed. request.not_found (404) also for a page with no picture."
            + SOURCE_SUMMARY,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "200", description = "The page's picture",
                    content = @Content(mediaType = WEBP, schema = @Schema(type = "string", format = "binary"))))
    void sourcePicture(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The material version's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("versionId") String versionId,
            @Parameter(description = "The page's number, from 1", schema = @Schema(type = "integer", format = "int32",
                    minimum = "1")) @PathVariable("page") String page,
            @Parameter(hidden = true) HttpServletResponse response) throws IOException {
        SourcePicture picture = parse(campaignId).flatMap(campaign -> parse(versionId).flatMap(version -> number(page)
                        .flatMap(wanted -> conversations.sourcePicture(campaign, version, wanted))))
                .orElseThrow(ConversationProblems::notFound);
        writePicture(picture, response);
    }

    @PostMapping(path = SEARCHES, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "createSearch", summary = "Search the campaign's scope by meaning or exact words (202): the"
            + " search and its job are recorded together, and the search is then read by id until it is done or failed."
            + " Only the campaign's current scope is searched (its tiers and materials switched on, each material's"
            + " ready version, and passages still being prepared for search), narrowed by the filters; a passage that"
            + " holds the exact query comes first. Only the account's newest 20 searches are kept. request.invalid"
            + " (400) for a blank query, one past 500 code points, an unknown tier or a malformed material id;"
            + " request.not_found (404) for a campaign that is not the account's; nothing is recorded then",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<SearchCreated> createSearch(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @RequestBody NewSearch body) {
        UUID campaign = parse(campaignId).orElseThrow(ConversationProblems::notFound);
        if (body == null) {
            throw ConversationProblems.invalid();
        }
        Tier tier = body.tier() == null ? null : Tier.of(body.tier()).orElseThrow(ConversationProblems::invalid);
        UUID material = body.materialId() == null ? null
                : parse(body.materialId()).orElseThrow(ConversationProblems::invalid);
        UUID created = searches.create(campaign, body.query(), tier, material);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(new SearchCreated(created));
    }

    @GetMapping(path = SEARCH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSearch", summary = "A search of the account: its query, filters and state, and once"
            + " done its results, each re-checked against its campaign's current scope narrowed by the filters (one"
            + " that left the scope since is not listed). request.not_found (404) for a search that is not the"
            + " account's (another account's, unknown, malformed, pruned, or of a campaign deleted since)",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<SearchView> search(
            @Parameter(description = "The search's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("searchId") String searchId) {
        Searches.Found found = parse(searchId).flatMap(searches::find).orElseThrow(ConversationProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SearchView.of(found));
    }

    /** A page's size: {@value Conversations#DEFAULT_LIMIT} without one, else 1 to 50, or {@code request.invalid}. */
    private static int limit(String limit) {
        if (limit == null) {
            return Conversations.DEFAULT_LIMIT;
        }
        if (!LIMIT.matcher(limit).matches() || Integer.parseInt(limit) > Conversations.MAX_LIMIT) {
            throw ConversationProblems.invalid();
        }
        return Integer.parseInt(limit);
    }

    /** The position a page starts at: 0 without one, else 0 to 100000, or {@code request.invalid}. */
    private static int offset(String offset) {
        if (offset == null) {
            return 0;
        }
        if (!OFFSET_TEXT.matcher(offset).matches() || Integer.parseInt(offset) > Conversations.MAX_OFFSET) {
            throw ConversationProblems.invalid();
        }
        return Integer.parseInt(offset);
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
