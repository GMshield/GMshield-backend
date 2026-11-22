package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.ConversationConflict;
import io.github.gmshield.conversations.data.ConversationRow;
import io.github.gmshield.conversations.data.ConversationStore;
import io.github.gmshield.conversations.data.EventBatchRow;
import io.github.gmshield.conversations.data.TurnReferenceRow;
import io.github.gmshield.conversations.data.TurnReferenceStore;
import io.github.gmshield.conversations.data.TurnRow;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.scope.service.CampaignScopes;
import io.github.gmshield.scope.service.Indexing;
import io.github.gmshield.scope.service.IndexingFailure;
import io.github.gmshield.scope.service.ScopeSettings;
import io.github.gmshield.scope.service.ScopeStatus;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.scope.service.SourcePage;
import io.github.gmshield.scope.service.SourcePicture;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * The GM's conversation (Story 2.4). Everything here acts for the owner of the work ({@link OwnerContext}).
 *
 * <ul>
 *   <li>{@link #current}: the current campaign, whether its scope (from Story 3.5 its stored settings,
 *       {@link CampaignScopes#settings}) has a source (an allowed source, or a
 *       pending passage of one), and how many of that scope's passages are still waiting for search
 *       ({@link Scopes#indexing}; null when nothing in it is pending), and how many could not be prepared for search,
 *       with whether a retry remains ({@link Scopes#status}; null when none). No campaign is
 *       {@code request.not_found}.</li>
 *   <li>{@link #ofCampaign} (Story 3.2): the owner's campaign's conversation and how many questions it holds; from
 *       Story 3.10 its latest {@code questions} conversation (the one a question with no conversation continues); empty
 *       when the campaign has none or is not the owner's. An archived campaign's conversation is found too.</li>
 *   <li>{@link #ask}: a question of 1 to {@value Scopes#MAX_QUESTION} code points, not blank (else
 *       {@code request.invalid}, nothing recorded), on a campaign of the owner (else {@code request.not_found}). In one
 *       transaction: the conversation (Story 3.10: the one named, a new one of an area, or the campaign's latest
 *       {@code questions} one; made on its first question), the turn with the question
 *       encrypted, its language ({@link Languages}) and the campaign's stored scope settings frozen on it (Story 3.5,
 *       {@link CampaignScopes#settings}: the switches and the materials off), and its
 *       {@code question.answer} job on the interactive lane.</li>
 *   <li>{@link #turn} and {@link #eventsAfter}: a turn of the owner, and its stored events after a sequence, decrypted,
 *       in order.</li>
 *   <li>{@link #references} (Story 2.5): a turn's stored source references, decrypted, by number, each marked
 *       {@code removed} as of now when its source is removed ({@link Scopes#removedVersions}).</li>
 *   <li>{@link #stop} (Story 2.6): the GM's stop of a turn of the owner (else {@code request.not_found}), in one
 *       transaction that first locks the turn's job row, the lock its batches and its outcome take: a {@code waiting}
 *       or {@code offered} job is cancelled, a {@code running} one keeps its claim (so the worker can still post usage
 *       and report), and the turn ends at once ({@link AnswerJobs}: references so far, {@code done} marked
 *       {@code stopped}) unless it has ended already. Repeating it changes nothing.</li>
 *   <li>{@link #referencePage} and {@link #referencePicture} (Story 2.7): a page of the version a stored source
 *       reference names, on the ground "a source reference stored with a turn of that campaign" (AD-9). Three checks
 *       come first: the owner's turn exists, its conversation's campaign is the owner's, and the turn has a reference
 *       with that number. {@link Scopes#referencePage} then serves the page from the stored version and material, never
 *       from an id the client sent. Empty for anything not served.</li>
 *   <li>{@link #sourcePage} and {@link #sourcePicture} (Story 3.5): a page of a version that is an allowed source of
 *       the campaign's current stored scope, on the ground "the current scope" ({@link CampaignScopes#sourcePage});
 *       empty for anything else, with no hint whether the version exists.</li>
 *   <li>Story 3.10, conversations by area. A campaign has many conversations, each in one {@link Area}, made by its
 *       first question (never empty). {@link #list} pages a campaign's conversations of one area, the most recently
 *       asked first, each with its name and question count; {@link #conversation} reads one; {@link #turns} reads its
 *       newest {@value #MAX_TURNS} turns, oldest first, each with its stored events as the stream sends them;
 *       {@link #rename} names it (1 to {@value #NAME_MAX} code points, trimmed, encrypted) on its version. A
 *       conversation's {@link #nameOf name} is its stored name, or else its first question's first
 *       {@value #DEFAULT_NAME} code points cut at a word boundary. A conversation is the owner's only while its campaign
 *       is: another owner's, an unknown one and one of a campaign being deleted are all empty.</li>
 *   <li>{@link #history} (Story 3.10): the earlier turns of a turn's conversation that reach the model as data: the
 *       last {@value #HISTORY_TURNS} before it that ended {@code done} and were not stopped, oldest first, each answer
 *       its joined delta texts cut at {@value #HISTORY_ANSWER} code points.</li>
 *   <li>Story 3.10 part B, deleting a conversation. {@link #deletion} counts what goes (its questions);
 *       {@link #delete} starts the recorded, resumable deletion of {@link SystemDeletion#deleteConversation}, whose
 *       conversations part ({@link SystemConversationsDeletion}) marks it {@code deleting} and cancels its answer jobs in
 *       one transaction with the record, then removes its rows once their claims lapse. From that commit the
 *       conversation is "not found" everywhere: its campaign's lists and counts, {@link #conversation}, {@link #turns},
 *       {@link #rename}, a question naming it, and the campaign's latest {@code questions} conversation; so are its
 *       turns ({@link #turn}, {@link #eventsAfter}, {@link #stop}, {@link #referencePage}, {@link #referencePicture}).
 *       The delete cancels its answer jobs, which ends their claims; a job call already under way when the
 *       conversation turned deleting still reads it ({@link #storedEvents}, {@link #history}) and ends normally.</li>
 * </ul>
 *
 * Lines carry ids and counts, never a question or an answer's text.
 */
@Service
public class Conversations {

    static final String TURN = "turn";
    static final String FIELD_QUESTION = "question";
    static final String BATCH = "turn_event_batch";
    static final String FIELD_PAYLOAD = "payload";
    private static final Logger log = LoggerFactory.getLogger(Conversations.class);
    static final String CONVERSATION = "conversation";
    static final String FIELD_NAME = "name";
    /** A conversation's name, in code points after trimming (Story 3.10). */
    public static final int NAME_MAX = 120;
    /** The default name: the first question's first words, at most this many code points (Story 3.10). */
    public static final int DEFAULT_NAME = 60;
    /** The most turns a conversation's turns read returns: its newest (Story 3.10). */
    public static final int MAX_TURNS = 200;
    /** How many earlier turns reach the model with a question (Story 3.10). */
    public static final int HISTORY_TURNS = 6;
    /** An earlier answer as the model reads it, at most this many code points (Story 3.10). */
    public static final int HISTORY_ANSWER = 4000;
    /** A list page's size: {@value #DEFAULT_LIMIT} unless asked, at most {@value #MAX_LIMIT} (Story 3.10). */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    /** The furthest a list page may start (Story 3.10). */
    public static final int MAX_OFFSET = 100_000;
    /** The job states a stop cancels: the worker holds no claim yet. */
    private static final Set<String> UNCLAIMED = Set.of("waiting", "offered");

    private final ConversationStore store;
    private final TurnReferenceStore referenceStore;
    private final Library library;
    private final Scopes scopes;
    private final CampaignScopes campaignScopes;
    private final Jobs jobs;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<AnswerJobs> answers;
    private final ObjectProvider<SystemDeletion> deletions;
    private final Clock clock;

    Conversations(ConversationStore store, TurnReferenceStore referenceStore, Library library, Scopes scopes,
                  CampaignScopes campaignScopes, Jobs jobs, Vault vault,
                  ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<AnswerJobs> answers,
                  ObjectProvider<SystemDeletion> deletions, ObjectProvider<Clock> clock) {
        this.store = store;
        this.referenceStore = referenceStore;
        this.library = library;
        this.scopes = scopes;
        this.campaignScopes = campaignScopes;
        this.jobs = jobs;
        this.vault = vault;
        this.transactions = transactions;
        this.answers = answers;
        this.deletions = deletions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * The current campaign, whether it has a source, its passages waiting for search (null: nothing pending), and those
     * that could not be prepared for search (null: none).
     */
    public record Current(UUID campaignId, boolean hasSources, Indexing indexing, IndexingFailure indexingFailed) {
    }

    /** A campaign's conversation and how many questions it holds (Story 3.2). */
    public record OfCampaign(UUID id, long questions) {
    }

    /** A recorded turn: its id, its conversation and its language (null when unsure). */
    public record Asked(UUID turnId, UUID conversationId, String language) {
    }

    /**
     * What a turn's {@code done} event says beside {@code stopped} (Story 3.8): its source references, its frozen
     * model-knowledge switch, and whether a {@code model} tier event was stored with it.
     */
    public record Ending(List<TurnReference> references, boolean modelKnowledge, boolean modelTier) {

        public Ending {
            references = List.copyOf(references);
        }
    }

    /** A turn of the owner: its id and its language. */
    public record Turn(UUID id, UUID conversationId, String language) {
    }

    /**
     * A conversation as a list shows it (Story 3.10): its name ({@link #nameOf}), how many questions it holds, when it
     * was made and when its newest question was asked, and its version.
     */
    public record Summary(UUID id, Area area, String name, long questions, Instant createdAt, Instant lastAskedAt,
                          long version) {
        @Override
        public String toString() {
            return "Summary[id=" + id + ", area=" + area + "]";
        }
    }

    /** One page of a list (Story 3.10), the offset of the next page (null on the last), and how many there are. */
    public record Page(List<Summary> items, Integer nextOffset, long total) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /** One conversation of the owner (Story 3.10): its campaign, area, name and version. */
    public record Opened(UUID id, UUID campaignId, Area area, String name, long version) {
        @Override
        public String toString() {
            return "Opened[id=" + id + ", version=" + version + "]";
        }
    }

    /**
     * A stored turn (Story 3.10): its question and language, when it was asked, and its stored events in order, each
     * with its data as the stream sends it ({@code done}'s built now from its references).
     */
    public record StoredTurn(UUID id, String question, String language, Instant createdAt, List<StoredEvent> events) {
        public StoredTurn {
            events = List.copyOf(events);
        }

        @Override
        public String toString() {
            return "StoredTurn[id=" + id + ", events=" + events.size() + "]";
        }
    }

    /** One stored event of a turn: its sequence, its type and its data, as the stream sends them (Story 3.10). */
    public record StoredEvent(long sequence, String type, ObjectNode data) {
        @Override
        public String toString() {
            return "StoredEvent[sequence=" + sequence + ", type=" + type + "]";
        }
    }

    /** An earlier turn as the model reads it (Story 3.10): its question and its answer's text. */
    public record Earlier(String question, String answer) {
        @Override
        public String toString() {
            return "Earlier[]";
        }
    }

    /** The current campaign, as the class says. */
    public Current current() {
        return reading(() -> {
            Library.Campaign campaign = library.currentCampaign().orElseThrow(ConversationProblems::notFound);
            // One look-up of the sources; a pending passage always belongs to an allowed source.
            ScopeStatus status = scopes.status(campaign.id(), campaignScopes.settings(campaign.id()));
            return new Current(campaign.id(), status.hasSources(), status.indexing(), status.failed());
        });
    }

    /** The owner's campaign's conversation, as the class says. */
    public Optional<OfCampaign> ofCampaign(UUID campaignId) {
        if (campaignId == null) {
            return Optional.empty();
        }
        return reading(() -> library.campaign(campaignId).flatMap(campaign -> store.latestConversation(campaign.id(),
                        ConversationRow.QUESTIONS))
                .map(conversation -> new OfCampaign(conversation.id(), store.turnCount(conversation.id()))));
    }

    /** Records a question on the owner's campaign, in its latest {@code questions} conversation, as the class says. */
    public Asked ask(UUID campaignId, String question) {
        return ask(campaignId, question, null, null);
    }

    /**
     * Records a question on the owner's campaign, as the class says. From Story 3.10: with {@code conversationId}, in
     * that conversation, which must be the owner's and this campaign's (else {@code request.not_found}) and of
     * {@code area} when one is given (else {@code request.invalid}); without it but with {@code area}, in a new
     * conversation of that area; with neither, in the campaign's latest {@code questions} conversation (made when it
     * has none). The conversation's last question moves to now.
     */
    public Asked ask(UUID campaignId, String question, UUID conversationId, Area area) {
        if (question == null || question.isBlank()
                || question.codePointCount(0, question.length()) > Scopes.MAX_QUESTION) {
            throw ConversationProblems.invalid();
        }
        UUID owner = OwnerContext.require();
        String language = Languages.detect(question);
        Asked asked = writing(() -> {
            if (campaignId == null || library.campaign(campaignId).isEmpty()) {
                throw ConversationProblems.notFound();
            }
            Instant now = now();
            ConversationRow conversation = conversationFor(owner, campaignId, conversationId, area, now);
            UUID turnId = Ids.newId();
            ScopeSettings settings = campaignScopes.settings(campaignId);
            JobRef job = jobs.create(AnswerJobs.KIND, Lane.INTERACTIVE, created -> store.insertTurn(new TurnRow(turnId,
                    owner, conversation.id(), created.id(), vault.encryptText(question(turnId, owner), question),
                    language, settings.campaignTier(), settings.systemTier(), settings.otherTier(),
                    settings.modelKnowledge(), settings.materialsOff(), now, null)));
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                    .addKeyValue("conversationId", conversation.id()).addKeyValue("area", conversation.area())
                    .addKeyValue("turnId", turnId)
                    .addKeyValue("jobId", job.id()).addKeyValue("language", language == null ? "unsure" : language)
                    .addKeyValue("otherTier", settings.otherTier())
                    .addKeyValue("materialsOff", settings.materialsOff().size())
                    .addKeyValue("questionLength", question.codePointCount(0, question.length()))
                    .log("conversation.turn_recorded");
            return new Asked(turnId, conversation.id(), language);
        });
        return asked;
    }

    /** The conversation a question goes to, as {@link #ask} says: continued (its last question now) or made now. */
    private ConversationRow conversationFor(UUID owner, UUID campaignId, UUID conversationId, Area area, Instant now) {
        if (conversationId != null) {
            ConversationRow found = store.findConversation(conversationId)
                    .filter(row -> row.campaignId().equals(campaignId)).orElseThrow(ConversationProblems::notFound);
            if (area != null && !area.word().equals(found.area())) {
                throw ConversationProblems.invalid();
            }
            store.markAsked(found.id(), now);
            return found;
        }
        Optional<ConversationRow> latest = area == null
                ? store.latestConversation(campaignId, ConversationRow.QUESTIONS) : Optional.empty();
        if (latest.isPresent()) {
            store.markAsked(latest.get().id(), now);
            return latest.get();
        }
        ConversationRow created = ConversationRow.started(Ids.newId(), owner, campaignId,
                (area == null ? Area.QUESTIONS : area).word(), now);
        store.insertConversation(created);
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("conversationId", created.id()).addKeyValue("area", created.area())
                .log("conversation.created");
        return created;
    }

    /**
     * The owner's campaign's conversations of {@code area} (Story 3.10), the most recently asked first, skipping the
     * first {@code offset} (0 to {@value #MAX_OFFSET}), at most {@code limit} (1 to {@value #MAX_LIMIT}); empty for a
     * campaign that is not the owner's.
     */
    public Optional<Page> list(UUID campaignId, Area area, int offset, int limit) {
        if (limit < 1 || limit > MAX_LIMIT || offset < 0 || offset > MAX_OFFSET || area == null) {
            throw new IllegalArgumentException("conversations.page_invalid");
        }
        if (campaignId == null) {
            return Optional.empty();
        }
        return reading(() -> library.campaign(campaignId).map(campaign -> {
            List<ConversationRow> rows = store.conversations(campaign.id(), area.word(), offset, limit + 1);
            List<ConversationRow> page = rows.size() > limit ? rows.subList(0, limit) : rows;
            List<UUID> ids = page.stream().map(ConversationRow::id).toList();
            Map<UUID, TurnRow> first = store.firstTurns(ids);
            Map<UUID, Long> counts = store.turnCounts(ids);
            List<Summary> items = page.stream().map(row -> new Summary(row.id(), area, nameOf(row, first.get(row.id())),
                    counts.getOrDefault(row.id(), 0L), row.createdAt(), row.lastAskedAt(), row.version())).toList();
            return new Page(items, rows.size() > limit ? offset + limit : null,
                    store.conversationCount(campaign.id(), area.word()));
        }));
    }

    /** The owner's conversation with this id (Story 3.10), as the class says; empty for anything else. */
    public Optional<Opened> conversation(UUID id) {
        return reading(() -> visible(id).map(this::opened));
    }

    /**
     * The owner's conversation's newest {@value #MAX_TURNS} turns (Story 3.10), oldest first, each with its question,
     * language and stored events as the stream sends them; empty for a conversation that is not the owner's.
     */
    public Optional<List<StoredTurn>> turns(UUID id) {
        return reading(() -> visible(id).map(conversation -> store.newestTurns(conversation.id(), MAX_TURNS).stream()
                .map(this::stored).toList()));
    }

    /**
     * Renames the owner's conversation (Story 3.10) on {@code version}: the name trimmed, 1 to {@value #NAME_MAX} code
     * points (else {@code request.conversation_name_invalid}), stored encrypted; the version rises by one. Empty for a
     * conversation that is not the owner's; {@code request.stale_version} when it has left {@code version}. Nothing
     * changes on a refusal.
     */
    public Optional<Opened> rename(UUID id, long version, String typedName) {
        String name = typedName == null ? "" : typedName.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > NAME_MAX) {
            throw ConversationProblems.nameInvalid();
        }
        UUID owner = OwnerContext.require();
        Optional<Opened> renamed = writing(() -> visible(id).flatMap(row -> {
            if (row.version() != version) {
                throw ConversationProblems.staleVersion();
            }
            try {
                return store.renameConversation(row.id(), version, vault.encryptText(name(row.id(), owner), name));
            } catch (ConversationConflict moved) {
                throw ConversationProblems.staleVersion();
            }
        }).map(this::opened));
        renamed.ifPresent(opened -> log.atInfo().addKeyValue("ownerId", owner).addKeyValue("conversationId", id)
                .addKeyValue("version", opened.version()).log("conversation.renamed"));
        return renamed;
    }

    /**
     * What goes with the owner's conversation if it is deleted (Story 3.10 part B): how many questions it holds; empty
     * for a conversation that is not the owner's (a deleting one included).
     */
    public Optional<Long> deletion(UUID id) {
        return reading(() -> visible(id).map(row -> store.turnCount(row.id())));
    }

    /**
     * Deletes the owner's conversation (Story 3.10 part B), as the class says: true when its deletion was recorded now;
     * false (nothing changes) for a conversation that is not the owner's, a deleting one included.
     */
    public boolean delete(UUID id) {
        UUID owner = OwnerContext.require();
        if (reading(() -> visible(id)).isEmpty()) {
            return false;
        }
        boolean recorded = deletions.getObject().deleteConversation(id);
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("conversationId", id).addKeyValue("recorded", recorded)
                .log("conversation.delete");
        return recorded;
    }

    /**
     * The earlier turns of the owner's turn's conversation that reach the model with it (Story 3.10), as the class says:
     * oldest first; empty for the first question.
     */
    List<Earlier> history(TurnRow turn) {
        return reading(() -> {
            List<TurnRow> newest = store.newestTurns(turn.conversationId(), MAX_TURNS);
            int at = newest.stream().map(TurnRow::id).toList().indexOf(turn.id());
            // A turn not among the newest is older than all of them: nothing listed came before it.
            List<TurnRow> before = at < 0 ? List.of() : newest.subList(0, at);
            List<Earlier> earlier = new ArrayList<>();
            for (int index = before.size() - 1; index >= 0 && earlier.size() < HISTORY_TURNS; index--) {
                TurnRow row = before.get(index);
                if (row.stoppedAt() != null) {
                    continue;
                }
                List<TurnEvent> events = storedEvents(row.id(), 0);
                boolean done = events.stream().anyMatch(event -> TurnEvent.DONE.equals(event.type())
                        && !event.stopped());
                boolean failed = events.stream().anyMatch(event -> TurnEvent.ERROR.equals(event.type()));
                if (!done || failed) {
                    continue;
                }
                String answer = events.stream().filter(event -> TurnEvent.DELTA.equals(event.type()))
                        .map(TurnEvent::text).filter(text -> text != null).collect(Collectors.joining());
                earlier.add(new Earlier(vault.decryptText(question(row.id(), row.ownerId()), row.question()),
                        cut(answer, HISTORY_ANSWER)));
            }
            Collections.reverse(earlier);
            return List.copyOf(earlier);
        });
    }

    /**
     * The conversation's name (Story 3.10): the stored one, or else its first question's first {@value #DEFAULT_NAME}
     * code points cut at a word boundary ({@code first} is its first turn; empty text without one).
     */
    private String nameOf(ConversationRow row, TurnRow first) {
        if (row.name() != null) {
            return vault.decryptText(name(row.id(), row.ownerId()), row.name());
        }
        if (first == null) {
            return "";
        }
        return Searches.firstWords(vault.decryptText(question(first.id(), first.ownerId()), first.question()),
                DEFAULT_NAME);
    }

    /** The owner's conversation, while its campaign is the owner's (not unknown, not being deleted). */
    private Optional<ConversationRow> visible(UUID id) {
        return id == null ? Optional.empty() : store.findConversation(id)
                .filter(row -> library.campaign(row.campaignId()).isPresent());
    }

    private Opened opened(ConversationRow row) {
        TurnRow first = row.name() == null ? store.firstTurns(List.of(row.id())).get(row.id()) : null;
        return new Opened(row.id(), row.campaignId(), Area.of(row.area()).orElseThrow(), nameOf(row, first),
                row.version());
    }

    /** A stored turn with its events as the stream sends them; a {@code done}'s data built now (Story 2.5). */
    private StoredTurn stored(TurnRow row) {
        List<TurnEvent> events = storedEvents(row.id(), 0);
        List<StoredEvent> stored = new ArrayList<>();
        for (TurnEvent event : events) {
            ObjectNode data = TurnEvent.DONE.equals(event.type())
                    ? TurnEvents.doneData(new Ending(references(row.id()), row.modelKnowledge(), row.modelKnowledge()
                            && events.stream().anyMatch(Conversations::modelTier)), event.stopped())
                    : TurnEvents.data(event);
            stored.add(new StoredEvent(event.sequence(), event.type(), data));
        }
        return new StoredTurn(row.id(), vault.decryptText(question(row.id(), row.ownerId()), row.question()),
                row.language(), row.createdAt(), stored);
    }

    private static boolean modelTier(TurnEvent event) {
        return TurnEvent.TIER.equals(event.type()) && TurnEvent.MODEL.equals(event.tier());
    }

    /** {@code text}'s first {@code max} code points. */
    private static String cut(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }

    /**
     * The owner's turn with this id; empty for an unknown id, another owner's, or (Story 3.10 part B) one of a deleting
     * conversation.
     */
    public Optional<Turn> turn(UUID turnId) {
        return turnId == null ? Optional.empty() : reading(() -> shown(turnId)
                .map(row -> new Turn(row.id(), row.conversationId(), row.language())));
    }

    /** The owner's turn, while its conversation is {@code ready} (Story 3.10 part B). */
    private Optional<TurnRow> shown(UUID turnId) {
        return store.findTurn(turnId).filter(turn -> store.findConversation(turn.conversationId()).isPresent());
    }

    /** Stops the owner's turn's answer, as the class says. */
    public void stop(UUID turnId) {
        UUID owner = OwnerContext.require();
        writing(() -> {
            TurnRow turn = (turnId == null ? Optional.<TurnRow>empty() : shown(turnId))
                    .orElseThrow(ConversationProblems::notFound);
            UUID jobId = turn.jobId();
            String state = jobs.lock(jobId).orElseThrow(() -> new IllegalStateException("conversations.job_missing"));
            // Under the job's lock, a batch or an outcome committed before is seen: AnswerJobs.stop reads the turn's
            // events after it, so a turn that has ended (by its outcome or an earlier stop) is left as it is.
            boolean cancelled = UNCLAIMED.contains(state) && jobs.cancel(jobId);
            boolean stopped = answers.getObject().stop(turn);
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("turnId", turn.id()).addKeyValue("jobId", jobId)
                    .addKeyValue("jobState", state).addKeyValue("cancelled", cancelled).addKeyValue("stopped", stopped)
                    .log("conversation.turn_stopped");
            return stopped;
        });
    }

    /**
     * The owner's turn's stored events after {@code sequence}, in order; none for (Story 3.10 part B) a turn of a
     * deleting conversation.
     */
    public List<TurnEvent> eventsAfter(UUID turnId, long sequence) {
        return reading(() -> turnId == null || shown(turnId).isEmpty() ? List.<TurnEvent>of()
                : storedEvents(turnId, sequence));
    }

    /**
     * The owner's turn's stored events after {@code sequence}, in order, whatever its conversation's state: for its
     * answer job, whose call may be under way when the conversation turns deleting (Story 3.10 part B), and for reads
     * that checked the turn already.
     */
    List<TurnEvent> storedEvents(UUID turnId, long sequence) {
        return reading(() -> {
            List<TurnEvent> events = new ArrayList<>();
            for (EventBatchRow row : store.batchesAfter(turnId, sequence)) {
                byte[] json = vault.decrypt(payload(row.id(), row.ownerId()), row.payload());
                TurnEvents.decode(json, row.firstSequence()).stream().filter(event -> event.sequence() > sequence)
                        .forEach(events::add);
            }
            return List.copyOf(events);
        });
    }

    /** The owner's turn's source references, by number, as the class says; empty for none or another owner's turn. */
    public List<TurnReference> references(UUID turnId) {
        return reading(() -> {
            List<TurnReferenceRow> rows = referenceStore.references(turnId);
            if (rows.isEmpty()) {
                return List.<TurnReference>of();
            }
            Set<UUID> removed = scopes.removedVersions(rows.stream().map(TurnReferenceRow::versionId)
                    .collect(Collectors.toUnmodifiableSet()));
            return rows.stream().map(row -> new TurnReference(row.number(),
                    vault.decryptText(TurnReferences.materialName(row.id(), row.ownerId()), row.materialName()),
                    TurnReferences.path(vault.decrypt(TurnReferences.sectionPath(row.id(), row.ownerId()),
                            row.sectionPath())),
                    row.page(), row.passageIds(), removed.contains(row.versionId()), row.tier())).toList();
        });
    }

    /**
     * The owner's turn's {@link Ending}: its references ({@link #references}), the model-knowledge switch frozen with
     * it (false for an unknown turn or another owner's), and whether one of its stored events is a {@code model} tier
     * marker.
     */
    public Ending ending(UUID turnId) {
        return reading(() -> {
            boolean modelKnowledge = turnId != null && store.findTurn(turnId).map(TurnRow::modelKnowledge)
                    .orElse(false);
            boolean modelTier = modelKnowledge && storedEvents(turnId, 0).stream().anyMatch(Conversations::modelTier);
            return new Ending(references(turnId), modelKnowledge, modelTier);
        });
    }

    /** Page {@code page} of the version the owner's turn's reference {@code number} names, as the class says. */
    public Optional<SourcePage> referencePage(UUID turnId, int number, int page) {
        Optional<SourcePage> served = reading(() -> cited(turnId, number).flatMap(cited -> scopes.referencePage(
                cited.campaignId(), cited.materialId(), cited.versionId(), page)));
        log.atInfo().addKeyValue("turnId", turnId).addKeyValue("number", number).addKeyValue("page", page)
                .addKeyValue("served", served.isPresent()).log("conversation.reference_page");
        return served;
    }

    /** The picture of that page, as {@link #referencePage} finds it; empty also for a page with no picture. */
    public Optional<SourcePicture> referencePicture(UUID turnId, int number, int page) {
        Optional<SourcePicture> served = reading(() -> cited(turnId, number).flatMap(cited -> scopes
                .referencePicture(cited.campaignId(), cited.materialId(), cited.versionId(), page)));
        log.atInfo().addKeyValue("turnId", turnId).addKeyValue("number", number).addKeyValue("page", page)
                .addKeyValue("served", served.isPresent()).log("conversation.reference_picture");
        return served;
    }

    /** Page {@code page} of a version in the owner's campaign's current scope, as the class says. */
    public Optional<SourcePage> sourcePage(UUID campaignId, UUID versionId, int page) {
        return campaignScopes.sourcePage(campaignId, versionId, page);
    }

    /** The picture of that page, as {@link #sourcePage} finds it; empty also for a page with no picture. */
    public Optional<SourcePicture> sourcePicture(UUID campaignId, UUID versionId, int page) {
        return campaignScopes.sourcePicture(campaignId, versionId, page);
    }

    /** What a stored reference names: its turn's campaign, and its material and version. */
    private record Cited(UUID campaignId, UUID materialId, UUID versionId) {
    }

    /** The owner's turn's reference {@code number}, when the turn's conversation's campaign is the owner's. */
    private Optional<Cited> cited(UUID turnId, int number) {
        if (turnId == null) {
            return Optional.empty();
        }
        return store.findTurn(turnId).flatMap(turn -> store.findConversation(turn.conversationId()))
                .filter(conversation -> library.campaign(conversation.campaignId()).isPresent())
                .flatMap(conversation -> referenceStore.references(turnId).stream()
                        .filter(row -> row.number() == number).findFirst()
                        .map(row -> new Cited(conversation.campaignId(), row.materialId(), row.versionId())));
    }

    static Binding question(UUID turnId, UUID owner) {
        return new Binding(TURN, FIELD_QUESTION, turnId, owner);
    }

    static Binding name(UUID conversationId, UUID owner) {
        return new Binding(CONVERSATION, FIELD_NAME, conversationId, owner);
    }

    static Binding payload(UUID batchId, UUID owner) {
        return new Binding(BATCH, FIELD_PAYLOAD, batchId, owner);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private <T> T writing(Supplier<T> work) {
        return new TransactionTemplate(transactions.getObject()).execute(status -> work.get());
    }

    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}
