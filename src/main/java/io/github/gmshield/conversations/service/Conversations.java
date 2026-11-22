package io.github.gmshield.conversations.service;

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
import java.util.List;
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
 *   <li>{@link #ofCampaign} (Story 3.2): the owner's campaign's conversation (each campaign has at most one) and how
 *       many questions it holds; empty when the campaign has none or is not the owner's. An archived campaign's
 *       conversation is found too.</li>
 *   <li>{@link #ask}: a question of 1 to {@value Scopes#MAX_QUESTION} code points, not blank (else
 *       {@code request.invalid}, nothing recorded), on a campaign of the owner (else {@code request.not_found}). In one
 *       transaction: the campaign's one conversation (made on the first question), the turn with the question
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
    private final Clock clock;

    Conversations(ConversationStore store, TurnReferenceStore referenceStore, Library library, Scopes scopes,
                  CampaignScopes campaignScopes, Jobs jobs, Vault vault,
                  ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<AnswerJobs> answers,
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

    /** A turn of the owner: its id and its language. */
    public record Turn(UUID id, UUID conversationId, String language) {
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
        return reading(() -> library.campaign(campaignId).flatMap(campaign -> store.conversationOfCampaign(campaign.id()))
                .map(conversation -> new OfCampaign(conversation.id(), store.turnCount(conversation.id()))));
    }

    /** Records a question on the owner's campaign, as the class says. */
    public Asked ask(UUID campaignId, String question) {
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
            ConversationRow conversation = store.conversationOfCampaign(campaignId).orElseGet(() -> {
                ConversationRow created = new ConversationRow(Ids.newId(), owner, campaignId, now);
                store.insertConversation(created);
                return created;
            });
            UUID turnId = Ids.newId();
            ScopeSettings settings = campaignScopes.settings(campaignId);
            JobRef job = jobs.create(AnswerJobs.KIND, Lane.INTERACTIVE, created -> store.insertTurn(new TurnRow(turnId,
                    owner, conversation.id(), created.id(), vault.encryptText(question(turnId, owner), question),
                    language, settings.campaignTier(), settings.systemTier(), settings.otherTier(),
                    settings.modelKnowledge(), settings.materialsOff(), now, null)));
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                    .addKeyValue("conversationId", conversation.id()).addKeyValue("turnId", turnId)
                    .addKeyValue("jobId", job.id()).addKeyValue("language", language == null ? "unsure" : language)
                    .addKeyValue("otherTier", settings.otherTier())
                    .addKeyValue("materialsOff", settings.materialsOff().size())
                    .addKeyValue("questionLength", question.codePointCount(0, question.length()))
                    .log("conversation.turn_recorded");
            return new Asked(turnId, conversation.id(), language);
        });
        return asked;
    }

    /** The owner's turn with this id; empty for an unknown id or another owner's. */
    public Optional<Turn> turn(UUID turnId) {
        return turnId == null ? Optional.empty() : reading(() -> store.findTurn(turnId)
                .map(row -> new Turn(row.id(), row.conversationId(), row.language())));
    }

    /** Stops the owner's turn's answer, as the class says. */
    public void stop(UUID turnId) {
        UUID owner = OwnerContext.require();
        writing(() -> {
            TurnRow turn = (turnId == null ? Optional.<TurnRow>empty() : store.findTurn(turnId))
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

    /** The owner's turn's stored events after {@code sequence}, in order. */
    public List<TurnEvent> eventsAfter(UUID turnId, long sequence) {
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
                    row.page(), row.passageIds(), removed.contains(row.versionId()))).toList();
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
