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
import io.github.gmshield.scope.service.ResolvedScope;
import io.github.gmshield.scope.service.ScopeSettings;
import io.github.gmshield.scope.service.Scopes;
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
 *   <li>{@link #current}: the current campaign and whether its starting scope has a source (an allowed source, or a
 *       pending passage of one). No campaign is {@code request.not_found}.</li>
 *   <li>{@link #ask}: a question of 1 to {@value Scopes#MAX_QUESTION} code points, not blank (else
 *       {@code request.invalid}, nothing recorded), on a campaign of the owner (else {@code request.not_found}). In one
 *       transaction: the campaign's one conversation (made on the first question), the turn with the question
 *       encrypted, its language ({@link Languages}) and {@link ScopeSettings#STARTING} frozen on it, and its
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
    private final Jobs jobs;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<AnswerJobs> answers;
    private final Clock clock;

    Conversations(ConversationStore store, TurnReferenceStore referenceStore, Library library, Scopes scopes, Jobs jobs,
                  Vault vault, ObjectProvider<PlatformTransactionManager> transactions,
                  ObjectProvider<AnswerJobs> answers, ObjectProvider<Clock> clock) {
        this.store = store;
        this.referenceStore = referenceStore;
        this.library = library;
        this.scopes = scopes;
        this.jobs = jobs;
        this.vault = vault;
        this.transactions = transactions;
        this.answers = answers;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** The current campaign and whether it has a source. */
    public record Current(UUID campaignId, boolean hasSources) {
    }

    /** A recorded turn: its id, its conversation and its language (null when unsure). */
    public record Asked(UUID turnId, UUID conversationId, String language) {
    }

    /** A turn of the owner: its id and its language. */
    public record Turn(UUID id, UUID conversationId, String language) {
    }

    /** The current campaign, as the class says. */
    public Current current() {
        Library.Campaign campaign = library.currentCampaign().orElseThrow(ConversationProblems::notFound);
        ResolvedScope scope = scopes.resolve(campaign.id(), ScopeSettings.STARTING);
        return new Current(campaign.id(), !scope.allowedSources().isEmpty() || !scope.pending().isEmpty());
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
            ScopeSettings settings = ScopeSettings.STARTING;
            JobRef job = jobs.create(AnswerJobs.KIND, Lane.INTERACTIVE, created -> store.insertTurn(new TurnRow(turnId,
                    owner, conversation.id(), created.id(), vault.encryptText(question(turnId, owner), question),
                    language, settings.campaignTier(), settings.systemTier(), settings.modelKnowledge(), now, null)));
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                    .addKeyValue("conversationId", conversation.id()).addKeyValue("turnId", turnId)
                    .addKeyValue("jobId", job.id()).addKeyValue("language", language == null ? "unsure" : language)
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
