package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.ConversationRow;
import io.github.gmshield.conversations.data.ConversationStore;
import io.github.gmshield.conversations.data.EventBatchRow;
import io.github.gmshield.conversations.data.TurnRow;
import io.github.gmshield.jobs.service.JobEvent;
import io.github.gmshield.jobs.service.JobEventBatch;
import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobPassage;
import io.github.gmshield.jobs.service.JobProblems;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.scope.service.ResolvedScope;
import io.github.gmshield.scope.service.ScopeSettings;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * The job of kind {@value #KIND} (Story 2.4): the answer to one turn, on the interactive lane, calling a model.
 *
 * <ul>
 *   <li>Its input is the question input of the turn's campaign within the scope settings frozen on the turn
 *       ({@link Scopes#questionInput}), and its passages are read within that scope ({@link Scopes#passages}).</li>
 *   <li>Each numbered batch of events the worker posts is stored once per claim, encrypted with the turn, its events
 *       taking the turn's next sequences; a number past the next one of its claim is
 *       {@code job.batch_out_of_order}. Once the turn has a batch, the job has delivered (no splice: it is never
 *       offered again).</li>
 *   <li>{@code completed} appends {@code done}, and the job's end in {@code failed} appends {@code error} with its
 *       code. A stream waiting on the turn is woken after each commit ({@link TurnStreams}).</li>
 * </ul>
 *
 * Lines carry ids and counts only, never the question or the answer's text.
 */
@Service
public class AnswerJobs implements JobHandler {

    public static final String KIND = "question.answer";
    private static final Logger log = LoggerFactory.getLogger(AnswerJobs.class);

    private final ConversationStore store;
    private final Scopes scopes;
    private final Vault vault;
    private final TurnStreams streams;
    private final Clock clock;

    AnswerJobs(ConversationStore store, Scopes scopes, Vault vault, TurnStreams streams, ObjectProvider<Clock> clock) {
        this.store = store;
        this.scopes = scopes;
        this.vault = vault;
        this.streams = streams;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Lane lane() {
        return Lane.INTERACTIVE;
    }

    @Override
    public boolean callsModel() {
        return true;
    }

    @Override
    public JsonNode input(JobRef job) {
        TurnRow turn = turnOf(job);
        String question = vault.decryptText(Conversations.question(turn.id(), turn.ownerId()), turn.question());
        return scopes.questionInput(scopeOf(turn), question);
    }

    @Override
    public List<JobPassage> passages(JobRef job, List<UUID> ids) {
        return scopes.passages(scopeOf(turnOf(job)), ids).stream().map(passage -> new JobPassage(passage.id(),
                passage.tier().word(), passage.text(), passage.sectionPath(), passage.page())).toList();
    }

    @Override
    public void events(JobRef job, JobEventBatch batch) {
        TurnRow turn = turnOf(job);
        int last = store.lastBatch(turn.id(), batch.claimId());
        if (batch.number() <= last) {
            log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("turnId", turn.id())
                    .addKeyValue("claimId", batch.claimId()).addKeyValue("batch", batch.number())
                    .log("conversation.batch_repeated");
            return;
        }
        if (batch.number() != last + 1) {
            throw JobProblems.batchOutOfOrder();
        }
        List<TurnEvent> events = batch.events().stream().map(AnswerJobs::turnEvent).toList();
        append(turn, batch.claimId(), batch.number(), events);
    }

    @Override
    public boolean delivered(JobRef job) {
        return store.turnOfJob(job.id()).map(turn -> store.hasPostedBatch(turn.id())).orElse(false);
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        append(turnOf(job), null, null, List.of(new TurnEvent(0, TurnEvent.DONE, null, null, null, null)));
    }

    @Override
    public void failed(JobRef job, String code) {
        store.turnOfJob(job.id()).ifPresent(turn ->
                append(turn, null, null, List.of(new TurnEvent(0, TurnEvent.ERROR, null, null, null, code))));
    }

    /** Stores the events after the turn's last sequence, encrypted, and wakes its streams after the commit. */
    private void append(TurnRow turn, UUID claimId, Integer number, List<TurnEvent> events) {
        long first = store.lastSequence(turn.id()) + 1;
        UUID rowId = Ids.newId();
        byte[] payload = vault.encrypt(Conversations.payload(rowId, turn.ownerId()), TurnEvents.encode(events));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        store.insertBatch(new EventBatchRow(rowId, turn.ownerId(), turn.id(), claimId, number, first,
                first + events.size() - 1, payload, now));
        log.atInfo().addKeyValue("jobId", turn.jobId()).addKeyValue("turnId", turn.id())
                .addKeyValue("batch", number == null ? 0 : number).addKeyValue("events", events.size())
                .addKeyValue("firstSequence", first).log("conversation.events_stored");
        UUID turnId = turn.id();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    streams.wake(turnId);
                }
            });
        } else {
            streams.wake(turnId);
        }
    }

    private TurnRow turnOf(JobRef job) {
        return store.turnOfJob(job.id()).orElseThrow(() -> new IllegalStateException("conversations.turn_missing"));
    }

    private ResolvedScope scopeOf(TurnRow turn) {
        ConversationRow conversation = store.findConversation(turn.conversationId())
                .orElseThrow(() -> new IllegalStateException("conversations.conversation_missing"));
        return scopes.resolve(conversation.campaignId(), new ScopeSettings(turn.campaignTier(), turn.systemTier(),
                false, turn.modelKnowledge()));
    }

    private static TurnEvent turnEvent(JobEvent event) {
        return JobEvent.DELTA.equals(event.type())
                ? new TurnEvent(0, TurnEvent.DELTA, event.text(), null, null, null)
                : new TurnEvent(0, TurnEvent.CITATION, null, event.passageId(), event.offset(), null);
    }
}
