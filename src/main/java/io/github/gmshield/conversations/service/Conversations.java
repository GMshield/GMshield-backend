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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 *       {@code question.answer} job, or {@code content.draft} for a draft (Story 5.3 part A), on the interactive
 *       lane.</li>
 *   <li>{@link #turn} and {@link #eventsAfter}: a turn of the owner, and its stored events after a sequence, decrypted,
 *       in order.</li>
 *   <li>{@link #references} (Story 2.5): a turn's stored source references, decrypted, by number, each marked
 *       {@code removed} as of now when its source is removed ({@link Scopes#removedVersions}); from Story 4.3 part C
 *       a record's reference with the record's name and fields as stored, never read from the record again; from
 *       Story 5.2 part B an artifact's reference is {@code removed} when its artifact is deleting or gone
 *       ({@link Scopes#removedArtifacts}).</li>
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
 *       last {@value #HISTORY_TURNS} before it that ended {@code done} and were neither stopped nor (Story 3.11)
 *       dismissed, oldest first, each answer its joined delta texts cut at {@value #HISTORY_ANSWER} code points.</li>
 *   <li>Story 3.10 part B, deleting a conversation. {@link #deletion} counts what goes (its questions);
 *       {@link #delete} starts the recorded, resumable deletion of {@link SystemDeletion#deleteConversation}, whose
 *       conversations part ({@link SystemConversationsDeletion}) marks it {@code deleting} and cancels its answer jobs in
 *       one transaction with the record, then removes its rows once their claims lapse. From that commit the
 *       conversation is "not found" everywhere: its campaign's lists and counts, {@link #conversation}, {@link #turns},
 *       {@link #rename}, a question naming it, and the campaign's latest {@code questions} conversation; so are its
 *       turns ({@link #turn}, {@link #eventsAfter}, {@link #stop}, {@link #referencePage}, {@link #referencePicture}).
 *       The delete cancels its answer jobs, which ends their claims; a job call already under way when the
 *       conversation turned deleting still reads it ({@link #storedEvents}, {@link #history}) and ends normally.</li>
 *   <li>Story 3.11, dismissing an answer. {@link #dismiss} records the GM's dismissal of an ended turn of the owner
 *       (stopped, or with a {@code done} or {@code error} event; else {@code conversation.turn_running}, nothing
 *       changes), {@link #restore} undoes it; both are safe to repeat, {@code request.not_found} for a turn that is not
 *       the owner's or is of a deleting conversation, and neither changes the conversation's version or last question
 *       time. From the dismissal's commit the turn, with its question and answer, is left out of {@link #history},
 *       {@link #turns}, the question counts of {@link #list}, {@link #ofCampaign} and {@link #deletion}. The default
 *       name stays the first question's, dismissed or not; the turn's own routes ({@link #turn},
 *       {@link #eventsAfter}, {@link #references}, {@link #referencePage}) still answer; and deleting the conversation
 *       removes it too. The history is built for each claim of a question's job, so a claim already made keeps the
 *       history it was given.</li>
 *   <li>Story 3.12, bookmarking an answer. {@link #bookmark} saves the answer of a turn of the owner whose last stored
 *       event is {@code done} and that was neither stopped nor silent (else {@code conversation.turn_not_done}, nothing
 *       changes);
 *       {@link #unbookmark} removes the bookmark whatever the turn's state. Both are safe to repeat (a second save
 *       keeps the first time), {@code request.not_found} for a turn that is not the owner's or is of a deleting
 *       conversation, and neither changes the conversation's version or last question time. {@link #saved} pages the
 *       owner's saved answers across all their campaigns, the newest saved first, each with its campaign's name, its
 *       conversation's area and {@link #nameOf name}, and an excerpt ({@link #excerpt}); it leaves out dismissed turns
 *       (their bookmark is kept, so an undo lists them again), turns of deleting conversations, and campaigns that are
 *       archived or being deleted. A bookmark gives an answer no standing as a source: {@link #history}, the scope
 *       and retrieval never read it. {@link #turns} says whether each turn is saved.</li>
 *   <li>Story 5.3 part A, drafts. A turn has a {@link TurnKind kind}: a question, or a draft the GM asked for in the
 *       {@code questions} area ({@link #ask(UUID, String, UUID, Area, TurnKind)}), written by a {@link DraftJobs} job
 *       from the same sources. A draft is an answer like any other: it is stopped, dismissed, bookmarked, read in
 *       {@link #turns} (with its kind) and sent in the {@link #history} of later turns, and it is never a source.</li>
 *   <li>Story 5.3 part B, the draft's proposal. A finished draft may have a {@link Proposal} (a suggested name and
 *       record type), which {@link DraftJobs} stores with the turn (the name encrypted, binding
 *       {@code turn}/{@code proposal_name}) before its {@code done}. The {@link Ending} read for the stream
 *       ({@link #ending}) and for {@link #turns} carries it, and {@code done} shows it only when the turn is neither
 *       stopped nor silent ({@link TurnEvents#doneData}). Nothing is written to the library.</li>
 *   <li>Story 5.4 part A, saving a draft. A draft turn that is done and neither stopped nor silent (the
 *       {@link #bookmark} rule, plus its kind) is offered for saving; nothing else ever is. Its {@link Saving save state}
 *       is worked out when it is read ({@link #turns}): {@code saved} while the artifact it was saved as is live in its
 *       campaign ({@link Scopes#removedArtifacts}), {@code declined} once the GM declined it or that artifact is
 *       deleting or gone, {@code offered} otherwise. {@link #saveable} reads what the {@code artifacts} module saves
 *       (the conversation, its campaign, the draft's text joined from its stored deltas and its references with their
 *       versions and materials), or refuses with {@code conversation.turn_not_saveable} or
 *       {@code conversation.turn_saved}; {@link #markSaved} records the save in the caller's transaction, or refuses
 *       with {@code conversation.turn_saved} (409) so that transaction rolls back. {@link #declineSave} ("Agora não")
 *       and {@link #reopenSave} ("Salvar como artefato") are safe to repeat, and refuse a turn that is not offered or
 *       is saved. None changes the turn's events: the conversation keeps the agent's text. Every refusal and
 *       {@code request.not_found} is as {@link #bookmark}'s.</li>
 *   <li>Story 5.4 part B, a conversation's saved artifacts. {@link #deletion} also lists the live artifacts saved from
 *       the conversation, read through the {@link ConversationArtifacts} port the {@code artifacts} module implements
 *       (this module never depends on that one), and {@link #delete(UUID, Set)} takes the ticked ones with the
 *       conversation in one transaction: a conversation not found deletes none of them. {@link #ready} tells which
 *       conversations are {@code ready}, so an artifact never links to a deleting one.</li>
 * </ul>
 *
 * Lines carry ids and counts, never a question or an answer's text.
 */
@Service
public class Conversations {

    static final String TURN = "turn";
    static final String FIELD_QUESTION = "question";
    static final String FIELD_PROPOSAL_NAME = "proposal_name";
    static final String FIELD_PARTY_SIZE = "party_size";
    static final String FIELD_PARTY_LEVEL = "party_level";
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
    /** A saved answer's excerpt, at most this many code points (Story 3.12). */
    public static final int EXCERPT = 280;
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
    private final ObjectProvider<ConversationArtifacts> savedArtifacts;
    private final Clock clock;

    Conversations(ConversationStore store, TurnReferenceStore referenceStore, Library library, Scopes scopes,
                  CampaignScopes campaignScopes, Jobs jobs, Vault vault,
                  ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<AnswerJobs> answers,
                  ObjectProvider<SystemDeletion> deletions, ObjectProvider<ConversationArtifacts> savedArtifacts,
                  ObjectProvider<Clock> clock) {
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
        this.savedArtifacts = savedArtifacts;
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

    /**
     * What goes with a conversation if it is deleted (Story 3.10 part B): how many questions it shows, and (Story 5.4
     * part B) its live saved artifacts, oldest first, which its delete may take.
     */
    public record Deletion(long questions, List<ConversationArtifacts.SavedArtifact> artifacts) {
        public Deletion {
            artifacts = List.copyOf(artifacts);
        }
    }

    /** A recorded turn: its id, its conversation and its language (null when unsure). */
    public record Asked(UUID turnId, UUID conversationId, String language) {
    }

    /**
     * What a turn's {@code done} event says beside {@code stopped} (Story 3.8): its source references, its frozen
     * model-knowledge switch, and whether a {@code model} tier event was stored with it. From Story 5.3 part B, a
     * draft's stored proposal, decrypted (null when it has none).
     */
    public record Ending(List<TurnReference> references, boolean modelKnowledge, boolean modelTier,
                         Proposal proposal) {

        public Ending {
            references = List.copyOf(references);
        }
    }

    /**
     * A finished draft's proposal (Story 5.3 part B): its suggested name and record type key (null for none).
     * {@code toString} names the type only when it is core, never the name.
     */
    public record Proposal(String name, String recordType) {
        @Override
        public String toString() {
            return "Proposal[recordType=" + Scopes.loggedType(recordType) + "]";
        }
    }

    /** A turn of the owner: its id and its language. */
    public record Turn(UUID id, UUID conversationId, String language) {
    }

    /** Where an offered draft's save card stands (Story 5.4 part A). */
    public enum SaveState {
        OFFERED("offered"), DECLINED("declined"), SAVED("saved");

        private final String word;

        SaveState(String word) {
            this.word = word;
        }

        /** Its word, as the API has it. */
        public String word() {
            return word;
        }
    }

    /** An offered draft's save state (Story 5.4 part A), and the artifact it was saved as (only when saved). */
    public record Saving(SaveState state, UUID artifactId) {
    }

    /**
     * One source reference of a draft as a save copies it (Story 5.4 part A): the reference, decrypted, and the
     * version and material it names (null for an artifact's). {@code toString} names the number only.
     */
    public record SavedSource(UUID versionId, UUID materialId, TurnReference reference) {
        @Override
        public String toString() {
            return "SavedSource[number=" + reference.number() + "]";
        }
    }

    /**
     * What a save of the owner's offered draft reads (Story 5.4 part A): its turn, conversation and campaign, the draft's
     * text (its joined deltas, as Markdown), its references by number, and the artifact its save state names (null when
     * never saved; else one deleted since). {@code toString} names ids and counts only.
     */
    public record Saveable(UUID turnId, UUID conversationId, UUID campaignId, String draft, List<SavedSource> sources,
                           UUID savedRecordId) {
        public Saveable {
            sources = List.copyOf(sources);
        }

        @Override
        public String toString() {
            return "Saveable[turnId=" + turnId + ", sources=" + sources.size() + "]";
        }
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
     * with its data as the stream sends it ({@code done}'s built now from its references); from Story 5.3 part A, its
     * kind; from Story 5.4 part A, its save state (null unless it is an offered draft).
     */
    public record StoredTurn(UUID id, String question, String language, Instant createdAt, List<StoredEvent> events,
                             boolean bookmarked, TurnKind kind, Saving saving) {
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

    /**
     * A saved answer (Story 3.12): its turn, conversation and campaign, the campaign's name, the conversation's area
     * and name, when it was saved, and its excerpt.
     */
    public record Saved(UUID turnId, UUID conversationId, UUID campaignId, String campaignName, Area area,
                        String conversationName, Instant savedAt, String excerpt) {
        @Override
        public String toString() {
            return "Saved[turnId=" + turnId + "]";
        }
    }

    /** One page of the saved answers (Story 3.12), the offset of the next page (null on the last), and the total. */
    public record SavedPage(List<Saved> items, Integer nextOffset, long total) {
        public SavedPage {
            items = List.copyOf(items);
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
        return ask(campaignId, question, conversationId, area, TurnKind.QUESTION);
    }

    /**
     * Records a turn of {@code kind} on the owner's campaign, as {@link #ask(UUID, String, UUID, Area)} says (Story 5.3
     * part A): a {@link TurnKind#DRAFT draft} gets a {@value DraftJobs#KIND} job instead of a {@value AnswerJobs#KIND}
     * one, and only in the {@code questions} area: a draft whose {@code area}, or whose conversation's area, is another
     * is {@code request.invalid} with the field {@code kind}, and nothing is recorded.
     */
    public Asked ask(UUID campaignId, String question, UUID conversationId, Area area, TurnKind kind) {
        if (question == null || question.isBlank()
                || question.codePointCount(0, question.length()) > Scopes.MAX_QUESTION) {
            throw ConversationProblems.invalid();
        }
        TurnKind asked = kind == null ? TurnKind.QUESTION : kind;
        if (asked == TurnKind.DRAFT && area != null && area != Area.QUESTIONS) {
            throw ConversationProblems.kindInvalid();
        }
        UUID owner = OwnerContext.require();
        String language = Languages.detect(question);
        return writing(() -> {
            Library.Campaign campaign = campaignId == null ? null : library.campaign(campaignId).orElse(null);
            if (campaign == null) {
                throw ConversationProblems.notFound();
            }
            Instant now = now();
            ConversationRow conversation = conversationFor(owner, campaignId, conversationId, area, asked, now);
            UUID turnId = Ids.newId();
            ScopeSettings settings = campaignScopes.settings(campaignId);
            // Story 7.1: the campaign's party, frozen with the turn, so a change applies from the next question.
            byte[] partySize = encryptParty(FIELD_PARTY_SIZE, turnId, owner, campaign.partySize());
            byte[] partyLevel = encryptParty(FIELD_PARTY_LEVEL, turnId, owner, campaign.partyLevel());
            JobRef job = jobs.create(asked.jobKind(), Lane.INTERACTIVE, created -> store.insertTurn(new TurnRow(turnId,
                    owner, conversation.id(), created.id(), vault.encryptText(question(turnId, owner), question),
                    language, settings.campaignTier(), settings.systemTier(), settings.otherTier(),
                    settings.modelKnowledge(), settings.materialsOff(), now, null, null, null, asked.word(), null,
                    null, null, null, partySize, partyLevel)));
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                    .addKeyValue("conversationId", conversation.id()).addKeyValue("area", conversation.area())
                    .addKeyValue("turnId", turnId).addKeyValue("kind", asked.word())
                    .addKeyValue("jobId", job.id()).addKeyValue("language", language == null ? "unsure" : language)
                    .addKeyValue("otherTier", settings.otherTier())
                    .addKeyValue("materialsOff", settings.materialsOff().size())
                    .addKeyValue("partySize", partySize != null).addKeyValue("partyLevel", partyLevel != null)
                    .addKeyValue("questionLength", question.codePointCount(0, question.length()))
                    .log("conversation.turn_recorded");
            return new Asked(turnId, conversation.id(), language);
        });
    }

    /**
     * The conversation a question goes to, as {@link #ask} says: continued (its last question now) or made now. A
     * draft continues only a {@code questions} conversation (Story 5.3 part A); this is checked before anything changes.
     */
    private ConversationRow conversationFor(UUID owner, UUID campaignId, UUID conversationId, Area area, TurnKind kind,
                                            Instant now) {
        if (conversationId != null) {
            ConversationRow found = store.findConversation(conversationId)
                    .filter(row -> row.campaignId().equals(campaignId)).orElseThrow(ConversationProblems::notFound);
            if (area != null && !area.word().equals(found.area())) {
                throw ConversationProblems.invalid();
            }
            if (kind == TurnKind.DRAFT && !ConversationRow.QUESTIONS.equals(found.area())) {
                throw ConversationProblems.kindInvalid();
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
        return reading(() -> visible(id).map(conversation -> {
            List<TurnRow> rows = store.newestTurns(conversation.id(), MAX_TURNS);
            // Story 5.4 part A: one look-up of the saved drafts' artifacts.
            Set<UUID> gone = scopes.removedArtifacts(conversation.campaignId(), rows.stream()
                    .map(TurnRow::savedRecordId).filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet()));
            return rows.stream().map(row -> stored(row, gone)).toList();
        }));
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
     * What goes with the owner's conversation if it is deleted (Story 3.10 part B): how many questions it holds, and
     * (Story 5.4 part B) the live artifacts saved from it, oldest first ({@link ConversationArtifacts#saved}), which its
     * delete may take; empty for a conversation that is not the owner's (a deleting one included).
     */
    public Optional<Deletion> deletion(UUID id) {
        return reading(() -> visible(id).map(row -> new Deletion(store.turnCount(row.id()),
                savedArtifacts.getObject().saved(row.id()))));
    }

    /**
     * Deletes the owner's conversation (Story 3.10 part B), as the class says, keeping every artifact saved from it:
     * {@link #delete(UUID, Set)} with none ticked.
     */
    public boolean delete(UUID id) {
        return delete(id, Set.of());
    }

    /**
     * Deletes the owner's conversation (Story 3.10 part B), as the class says, with (Story 5.4 part B) the artifacts
     * saved from it among {@code artifactIds}: in one transaction, the conversation's deletion is recorded
     * ({@link SystemDeletion#deleteConversation}), then each id that is one of its live saved artifacts starts its
     * recorded deletion ({@link ConversationArtifacts#deleteSaved}; any other id is ignored). True when it was recorded now;
     * false (nothing changes, those artifacts' deletions roll back too) for a conversation that is not the owner's, a
     * deleting one included.
     */
    public boolean delete(UUID id, Set<UUID> artifactIds) {
        UUID owner = OwnerContext.require();
        Set<UUID> ticked = Set.copyOf(artifactIds);
        Boolean recorded = new TransactionTemplate(transactions.getObject()).execute(status -> {
            Optional<ConversationRow> found = visible(id);
            if (found.isEmpty()) {
                return false;
            }
            ConversationRow row = found.get();
            if (!deletions.getObject().deleteConversation(row.id())) {
                // Recorded already (another tab's delete won): not found, and no artifact is touched.
                status.setRollbackOnly();
                return false;
            }
            // In the same transaction: an artifact deletion that fails rolls the conversation's back too.
            savedArtifacts.getObject().deleteSaved(row.campaignId(), row.id(), ticked);
            return true;
        });
        boolean started = Boolean.TRUE.equals(recorded);
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("conversationId", id).addKeyValue("recorded", started)
                .addKeyValue("artifactsTicked", ticked.size()).log("conversation.delete");
        return started;
    }

    /**
     * Which of these ids are the owner's {@code ready} conversations (Story 5.4 part B: an artifact links to the
     * conversation it was saved from only then); one that is deleting, gone, of a campaign not readable, another
     * owner's or unknown is left out.
     */
    public Set<UUID> ready(Set<UUID> ids) {
        OwnerContext.require();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return reading(() -> ids.stream().filter(Objects::nonNull).filter(id -> visible(id).isPresent())
                .collect(Collectors.toUnmodifiableSet()));
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
                String answer = answerText(events);
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

    /**
     * A stored turn with its events as the stream sends them; a {@code done}'s data built now (Story 2.5). {@code gone}
     * holds the saved drafts' artifacts that are deleting or gone (Story 5.4 part A).
     */
    private StoredTurn stored(TurnRow row, Set<UUID> gone) {
        List<TurnEvent> events = storedEvents(row.id(), 0);
        List<TurnReference> references = references(row.id());
        List<StoredEvent> stored = new ArrayList<>();
        for (TurnEvent event : events) {
            ObjectNode data = TurnEvent.DONE.equals(event.type())
                    ? TurnEvents.doneData(new Ending(references, row.modelKnowledge(), row.modelKnowledge()
                            && events.stream().anyMatch(Conversations::modelTier), proposal(row)), event.stopped())
                    : TurnEvents.data(event);
            stored.add(new StoredEvent(event.sequence(), event.type(), data));
        }
        return new StoredTurn(row.id(), vault.decryptText(question(row.id(), row.ownerId()), row.question()),
                row.language(), row.createdAt(), stored, row.bookmarkedAt() != null,
                TurnKind.of(row.kind()).orElse(TurnKind.QUESTION),
                offered(row, events, references) ? saving(row, gone) : null);
    }

    /**
     * The save state of an offered draft (Story 5.4 part A), as the class says; {@code gone} holds the artifacts that
     * are deleting or gone among those it may name.
     */
    private static Saving saving(TurnRow row, Set<UUID> gone) {
        UUID saved = row.savedRecordId();
        if (saved != null && !gone.contains(saved)) {
            return new Saving(SaveState.SAVED, saved);
        }
        if (saved != null || row.saveDeclinedAt() != null) {
            return new Saving(SaveState.DECLINED, null);
        }
        return new Saving(SaveState.OFFERED, null);
    }

    /** The turn's stored proposal, its name decrypted (Story 5.3 part B); null when it has none. */
    private Proposal proposal(TurnRow row) {
        return row.proposed() ? new Proposal(vault.decryptText(proposalName(row.id(), row.ownerId()),
                row.proposalName()), row.proposalType()) : null;
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

    /** Dismisses the owner's ended turn (Story 3.11), as the class says. */
    public void dismiss(UUID turnId) {
        UUID owner = OwnerContext.require();
        boolean changed = writing(() -> {
            TurnRow turn = (turnId == null ? Optional.<TurnRow>empty() : shown(turnId))
                    .orElseThrow(ConversationProblems::notFound);
            if (turn.dismissedAt() != null) {
                return false;
            }
            // A turn that has ended never runs again, so no lock is needed: one seen ended stays ended.
            if (!ended(turn)) {
                throw ConversationProblems.turnRunning();
            }
            return store.markDismissed(turn.id(), now());
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("turnId", turnId).addKeyValue("changed", changed)
                .log("conversation.turn_dismissed");
    }

    /** Shows the owner's dismissed turn again (Story 3.11), as the class says. */
    public void restore(UUID turnId) {
        UUID owner = OwnerContext.require();
        boolean changed = writing(() -> {
            TurnRow turn = (turnId == null ? Optional.<TurnRow>empty() : shown(turnId))
                    .orElseThrow(ConversationProblems::notFound);
            return turn.dismissedAt() != null && store.clearDismissed(turn.id());
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("turnId", turnId).addKeyValue("changed", changed)
                .log("conversation.turn_restored");
    }

    /** Saves the answer of the owner's done turn (Story 3.12), as the class says. */
    public void bookmark(UUID turnId) {
        UUID owner = OwnerContext.require();
        boolean changed = writing(() -> {
            TurnRow turn = (turnId == null ? Optional.<TurnRow>empty() : shown(turnId))
                    .orElseThrow(ConversationProblems::notFound);
            if (turn.bookmarkedAt() != null) {
                return false;
            }
            // A done turn never runs again, so no lock is needed: one seen done stays done.
            if (!done(turn)) {
                throw ConversationProblems.turnNotDone();
            }
            return store.markBookmarked(turn.id(), now());
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("turnId", turnId).addKeyValue("changed", changed)
                .log("conversation.turn_bookmarked");
    }

    /** Removes the bookmark of the owner's turn (Story 3.12), as the class says. */
    public void unbookmark(UUID turnId) {
        UUID owner = OwnerContext.require();
        boolean changed = writing(() -> {
            TurnRow turn = (turnId == null ? Optional.<TurnRow>empty() : shown(turnId))
                    .orElseThrow(ConversationProblems::notFound);
            // No check of the row read here: a bookmark committed since is cleared too (the update checks it is set).
            return store.clearBookmarked(turn.id());
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("turnId", turnId).addKeyValue("changed", changed)
                .log("conversation.turn_unbookmarked");
    }

    /**
     * The owner's saved answers (Story 3.12), as the class says, the newest saved first, skipping the first
     * {@code offset} (0 to {@value #MAX_OFFSET}), at most {@code limit} (1 to {@value #MAX_LIMIT}).
     */
    public SavedPage saved(int offset, int limit) {
        if (limit < 1 || limit > MAX_LIMIT || offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("conversations.page_invalid");
        }
        OwnerContext.require();
        return reading(() -> {
            // A campaign archived or being deleted is left out: the places hold the ready ones, archived or not.
            Map<UUID, Library.Campaign> campaigns = new HashMap<>();
            library.places(store.savedCampaigns(), List.of()).campaigns().forEach((id, campaign) -> {
                if (!campaign.archived()) {
                    campaigns.put(id, campaign);
                }
            });
            if (campaigns.isEmpty()) {
                return new SavedPage(List.of(), null, 0);
            }
            List<TurnRow> rows = store.savedTurns(campaigns.keySet(), offset, limit + 1);
            List<TurnRow> page = rows.size() > limit ? rows.subList(0, limit) : rows;
            Map<UUID, ConversationRow> conversationsById = new HashMap<>();
            for (TurnRow row : page) {
                // A conversation marked deleting since the page was read is left out, with its rows.
                store.findConversation(row.conversationId())
                        .ifPresent(found -> conversationsById.put(found.id(), found));
            }
            Map<UUID, TurnRow> first = store.firstTurns(conversationsById.keySet());
            List<Saved> items = new ArrayList<>();
            for (TurnRow row : page) {
                ConversationRow conversation = conversationsById.get(row.conversationId());
                if (conversation == null) {
                    continue;
                }
                Library.Campaign campaign = campaigns.get(conversation.campaignId());
                items.add(new Saved(row.id(), conversation.id(), campaign.id(), campaign.name(),
                        Area.of(conversation.area()).orElseThrow(), nameOf(conversation, first.get(conversation.id())),
                        row.bookmarkedAt(), excerpt(answerText(storedEvents(row.id(), 0)))));
            }
            return new SavedPage(items, rows.size() > limit ? offset + limit : null,
                    store.savedCount(campaigns.keySet()));
        });
    }

    /** An answer's text: its joined {@code delta} texts (they hold no citation marker). */
    private static String answerText(List<TurnEvent> events) {
        return events.stream().filter(event -> TurnEvent.DELTA.equals(event.type())).map(TurnEvent::text)
                .filter(text -> text != null).collect(Collectors.joining());
    }

    /**
     * A saved answer's excerpt (Story 3.12): its Markdown removed ({@link Markdown#plain}), its white space flattened,
     * then cut at a word boundary to at most {@value #EXCERPT} code points ({@link Searches#firstWords(String, int)}).
     */
    static String excerpt(String answer) {
        return Searches.firstWords(Markdown.plain(answer), EXCERPT);
    }

    /**
     * Whether the turn is done and can be saved (Story 3.12): not stopped, its last stored event a {@code done} event
     * that is not marked stopped, and not silent (it has a source reference, or a {@code model} tier part on a turn whose
     * model knowledge was on: the same rule as the {@code done} event's {@code silent}, {@link TurnEvents#doneData}).
     */
    private boolean done(TurnRow turn) {
        if (turn.stoppedAt() != null) {
            return false;
        }
        return done(turn, storedEvents(turn.id(), 0), references(turn.id()));
    }

    /** {@link #done(TurnRow)}, given the turn's stored events and references. */
    private static boolean done(TurnRow turn, List<TurnEvent> events, List<TurnReference> references) {
        if (turn.stoppedAt() != null || events.isEmpty() || !TurnEvent.DONE.equals(events.getLast().type())
                || events.getLast().stopped()) {
            return false;
        }
        return !references.isEmpty() || turn.modelKnowledge() && events.stream().anyMatch(Conversations::modelTier);
    }

    /**
     * Whether the turn is offered for saving (Story 5.4 part A): a {@link TurnKind#DRAFT draft} that is {@link #done}
     * (not stopped, not silent), given its stored events and references.
     */
    private static boolean offered(TurnRow turn, List<TurnEvent> events, List<TurnReference> references) {
        return TurnRow.DRAFT.equals(turn.kind()) && done(turn, events, references);
    }

    /**
     * What a save of the owner's turn reads (Story 5.4 part A), as the class says, in the caller's transaction or a read
     * of its own: {@code request.not_found} for a turn that is not the owner's or is of a deleting conversation;
     * {@code conversation.turn_not_saveable} for one that is not offered; {@code conversation.turn_saved} for one saved
     * as a live artifact.
     */
    public Saveable saveable(UUID turnId) {
        return reading(() -> {
            Offered offered = offeredDraft(turnId);
            if (offered.saving().state() == SaveState.SAVED) {
                throw ConversationProblems.turnSaved();
            }
            TurnRow turn = offered.turn();
            Map<Integer, TurnReferenceRow> rows = new HashMap<>();
            referenceStore.references(turn.id()).forEach(row -> rows.put(row.number(), row));
            List<SavedSource> sources = offered.references().stream().map(reference -> {
                TurnReferenceRow row = rows.get(reference.number());
                return new SavedSource(row == null ? null : row.versionId(), row == null ? null : row.materialId(),
                        reference);
            }).toList();
            return new Saveable(turn.id(), turn.conversationId(), offered.campaignId(), answerText(offered.events()),
                    sources, turn.savedRecordId());
        });
    }

    /**
     * Records the save of the owner's draft read by {@link #saveable} as the artifact {@code recordId} (Story 5.4 part
     * A), in the caller's transaction, which must exist; {@code conversation.turn_saved} (409) when another save got
     * there first, so the caller's transaction rolls back.
     */
    public void markSaved(Saveable saveable, UUID recordId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("conversations.transaction_required");
        }
        OwnerContext.require();
        if (!store.markSaved(saveable.turnId(), saveable.savedRecordId(), recordId, now())) {
            throw ConversationProblems.turnSaved();
        }
    }

    /** Declines the save of the owner's offered draft (Story 5.4 part A, "Agora não"), as the class says. */
    public void declineSave(UUID turnId) {
        UUID owner = OwnerContext.require();
        boolean changed = writing(() -> {
            Offered offered = offeredDraft(turnId);
            return switch (offered.saving().state()) {
                case SAVED -> throw ConversationProblems.turnSaved();
                case DECLINED -> false;
                case OFFERED -> store.markSaveDeclined(offered.turn().id(), now());
            };
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("turnId", turnId).addKeyValue("changed", changed)
                .log("conversation.save_declined");
    }

    /** Offers the owner's declined draft for saving again (Story 5.4 part A, "Salvar como artefato"), as the class says. */
    public void reopenSave(UUID turnId) {
        UUID owner = OwnerContext.require();
        boolean changed = writing(() -> {
            Offered offered = offeredDraft(turnId);
            return switch (offered.saving().state()) {
                case SAVED -> throw ConversationProblems.turnSaved();
                case OFFERED -> false;
                // A saved artifact deleted since is let go, so a new save may follow.
                case DECLINED -> store.clearSaveDeclined(offered.turn().id(), offered.turn().savedRecordId());
            };
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("turnId", turnId).addKeyValue("changed", changed)
                .log("conversation.save_reopened");
    }

    /** An offered draft as read now: its row, campaign, events, references and save state. */
    private record Offered(TurnRow turn, UUID campaignId, List<TurnEvent> events, List<TurnReference> references,
                           Saving saving) {
    }

    /**
     * The owner's offered draft (Story 5.4 part A), in the caller's transaction: {@code request.not_found} for a turn
     * that is not the owner's or is of a deleting conversation, {@code conversation.turn_not_saveable} for one that is
     * not offered. A done draft never runs again, so no lock is needed: one seen done stays done.
     */
    private Offered offeredDraft(UUID turnId) {
        TurnRow turn = (turnId == null ? Optional.<TurnRow>empty() : shown(turnId))
                .orElseThrow(ConversationProblems::notFound);
        ConversationRow conversation = store.findConversation(turn.conversationId())
                .orElseThrow(ConversationProblems::notFound);
        List<TurnEvent> events = storedEvents(turn.id(), 0);
        List<TurnReference> references = references(turn.id());
        if (!offered(turn, events, references)) {
            throw ConversationProblems.turnNotSaveable();
        }
        Set<UUID> gone = turn.savedRecordId() == null ? Set.of()
                : scopes.removedArtifacts(conversation.campaignId(), Set.of(turn.savedRecordId()));
        return new Offered(turn, conversation.campaignId(), events, references, saving(turn, gone));
    }

    /** Whether the turn has ended (Story 3.11): stopped, or its last stored event is {@code done} or {@code error}. */
    private boolean ended(TurnRow turn) {
        if (turn.stoppedAt() != null) {
            return true;
        }
        long last = store.lastSequence(turn.id());
        return last > 0 && storedEvents(turn.id(), last - 1).stream().anyMatch(TurnEvent::last);
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
                    .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet()));
            Set<UUID> removedArtifacts = removedArtifacts(turnId, rows);
            return rows.stream().map(row -> row.artifact()
                    // Story 5.1 part B: an artifact's reference has no place; from Story 5.2 part B it is removed once
                    // its artifact is deleting or gone.
                    ? new TurnReference(row.number(), null, List.of(), 0, List.of(),
                    removedArtifacts.contains(row.record().recordId()), row.tier(), cited(row))
                    : new TurnReference(row.number(),
                    vault.decryptText(TurnReferences.materialName(row.id(), row.ownerId()), row.materialName()),
                    TurnReferences.path(vault.decrypt(TurnReferences.sectionPath(row.id(), row.ownerId()),
                            row.sectionPath())),
                    row.page(), row.passageIds(), removed.contains(row.versionId()), row.tier(), cited(row)))
                    .toList();
        });
    }

    /**
     * Which artifacts the turn's artifact references name are removed (Story 5.2 part B): deleting or gone, worked out
     * now through the scope; none when no reference names an artifact. Runs in the caller's read transaction.
     */
    private Set<UUID> removedArtifacts(UUID turnId, List<TurnReferenceRow> rows) {
        Set<UUID> ids = rows.stream().filter(TurnReferenceRow::artifact).map(row -> row.record().recordId())
                .collect(Collectors.toUnmodifiableSet());
        if (ids.isEmpty()) {
            return Set.of();
        }
        UUID campaignId = store.findTurn(turnId)
                .flatMap(turn -> store.findConversationAnyState(turn.conversationId()))
                .map(ConversationRow::campaignId).orElse(null);
        return scopes.removedArtifacts(campaignId, ids);
    }

    /** The record a stored reference names (Story 4.3 part C), decrypted; null for a passage place's reference. */
    private TurnReference.Cited cited(TurnReferenceRow row) {
        TurnReferenceRow.Recorded record = row.record();
        if (record == null) {
            return null;
        }
        return new TurnReference.Cited(record.recordId(), record.recordType(),
                vault.decryptText(TurnReferences.recordName(row.id(), row.ownerId()), record.name()),
                TurnReferences.fields(vault.decrypt(TurnReferences.recordFields(row.id(), row.ownerId()),
                        record.fields())), record.artifactVersion());
    }

    /**
     * The owner's turn's {@link Ending}: its references ({@link #references}), the model-knowledge switch frozen with
     * it (false for an unknown turn or another owner's), whether one of its stored events is a {@code model} tier
     * marker, and (Story 5.3 part B) its stored proposal.
     */
    public Ending ending(UUID turnId) {
        return reading(() -> {
            Optional<TurnRow> row = turnId == null ? Optional.empty() : store.findTurn(turnId);
            boolean modelKnowledge = row.map(TurnRow::modelKnowledge).orElse(false);
            boolean modelTier = modelKnowledge && storedEvents(turnId, 0).stream().anyMatch(Conversations::modelTier);
            return new Ending(references(turnId), modelKnowledge, modelTier, row.map(this::proposal).orElse(null));
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
                        // Story 5.1 part B: an artifact's reference has no page or picture.
                        .filter(row -> row.number() == number && !row.artifact()).findFirst()
                        .map(row -> new Cited(conversation.campaignId(), row.materialId(), row.versionId())));
    }

    /** A frozen party value (Story 7.1), encrypted as its decimal text under its turn binding; null stays null. */
    private byte[] encryptParty(String field, UUID turnId, UUID owner, Integer value) {
        return value == null ? null : vault.encryptText(party(field, turnId, owner), value.toString());
    }

    /**
     * The binding of a party value frozen on a turn (Story 7.1, {@code turn.party_size} or {@code turn.party_level}):
     * {@code field} is {@value #FIELD_PARTY_SIZE} or {@value #FIELD_PARTY_LEVEL}.
     */
    static Binding party(String field, UUID turnId, UUID owner) {
        return new Binding(TURN, field, turnId, owner);
    }

    static Binding question(UUID turnId, UUID owner) {
        return new Binding(TURN, FIELD_QUESTION, turnId, owner);
    }

    /** The binding of a draft's proposed name (Story 5.3 part B, {@code turn.proposal_name}). */
    static Binding proposalName(UUID turnId, UUID owner) {
        return new Binding(TURN, FIELD_PROPOSAL_NAME, turnId, owner);
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
