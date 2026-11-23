package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.ConversationRow;
import io.github.gmshield.conversations.data.ConversationStore;
import io.github.gmshield.conversations.data.EventBatchRow;
import io.github.gmshield.conversations.data.TurnReferenceRow;
import io.github.gmshield.conversations.data.TurnReferenceStore;
import io.github.gmshield.conversations.data.TurnRow;
import io.github.gmshield.jobs.service.JobEvent;
import io.github.gmshield.jobs.service.JobEventBatch;
import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobPassage;
import io.github.gmshield.jobs.service.JobProblems;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.scope.service.CitedPlace;
import io.github.gmshield.scope.service.ResolvedScope;
import io.github.gmshield.scope.service.ScopeSettings;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.scope.service.Tier;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The job of kind {@value #KIND} (Story 2.4): the answer to one turn, on the interactive lane, calling a model.
 *
 * <ul>
 *   <li>Its input is the question input of the turn's campaign within the scope settings frozen on the turn
 *       ({@link Scopes#questionInput}), and its passages are read within that scope ({@link Scopes#passages}). From
 *       Story 3.10 (internal contract 1.16.0) it adds {@code history: [{question, answer}]}, the conversation's earlier
 *       turns oldest first ({@link Conversations#history}; from Story 3.11 a dismissed turn is never among them, nor a
 *       stopped or failed one), built for each claim, so a claim already made keeps the history it was given: data for
 *       the model, never a source and never searched with; the line carries its count only.</li>
 *   <li>Each numbered batch of events the worker posts is stored once per claim, encrypted with the turn, its events
 *       taking the turn's next sequences; a number past the next one of its claim is
 *       {@code job.batch_out_of_order}. Once the turn has a batch, the job has delivered (no splice: it is never
 *       offered again).</li>
 *   <li>{@code completed} expands the turn's citations into source references (Story 2.5), then appends
 *       {@code done}; the job's end in {@code failed} appends {@code error} with its code and stores no reference. A
 *       stream waiting on the turn is woken after each commit ({@link TurnStreams}).</li>
 *   <li>The expansion runs in the transaction that ends the job: the passage ids of the turn's stored citations, in
 *       sequence order, are read again within the turn's scope ({@link Scopes#references}), which drops an unknown,
 *       foreign or out-of-scope id; the places left become references ({@link TurnReferences#group}), stored by value
 *       with the material's name and the section path encrypted, and with the tier of its source in that scope (Story
 *       3.7: frozen at answer time, as the scope labelled it). No reference and no {@code model} tier marker make the
 *       answer {@code silent}.</li>
 *   <li>A {@code tier} marker (Story 3.7) is stored with the batch's events only when its tier has a source in the
 *       turn's frozen scope; one with no source is dropped, never relayed. A {@code model} marker (Story 3.8) is
 *       stored only when the turn's frozen model-knowledge switch is on.</li>
 *   <li>The GM's stop (Story 2.6, {@link Conversations#stop}) ends the turn at once: unless it has a final event, its
 *       citations so far become references as above, {@code done} marked {@code stopped} is appended and
 *       {@code turn.stopped_at} is set. From then on a batch stores no event (its usage is still recorded), the answer
 *       to each batch is to stop ({@link #stopRequested}), the job counts as delivered, so it is never offered again,
 *       and {@code completed} or {@code failed} adds no event and no reference: a turn has one final event.</li>
 * </ul>
 *
 * Lines carry ids and counts only, never the question or the answer's text.
 */
@Service
public class AnswerJobs implements JobHandler {

    public static final String KIND = "question.answer";
    private static final Logger log = LoggerFactory.getLogger(AnswerJobs.class);

    private final ConversationStore store;
    private final TurnReferenceStore references;
    private final Conversations conversations;
    private final Scopes scopes;
    private final Vault vault;
    private final TurnStreams streams;
    private final Clock clock;

    AnswerJobs(ConversationStore store, TurnReferenceStore references, Conversations conversations, Scopes scopes,
               Vault vault, TurnStreams streams, ObjectProvider<Clock> clock) {
        this.store = store;
        this.references = references;
        this.conversations = conversations;
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
        ObjectNode input = scopes.questionInput(scopeOf(turn, true), question);
        // Story 3.10: the conversation's earlier turns, as data for the model only (never retrieval, never a source).
        List<Conversations.Earlier> earlier = conversations.history(turn);
        ArrayNode history = input.putArray("history");
        for (Conversations.Earlier one : earlier) {
            history.addObject().put("question", one.question()).put("answer", one.answer());
        }
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("turnId", turn.id())
                .addKeyValue("conversationId", turn.conversationId()).addKeyValue("history", earlier.size())
                .log("conversation.history_read");
        return input;
    }

    @Override
    public List<JobPassage> passages(JobRef job, List<UUID> ids) {
        return scopes.passages(scopeOf(turnOf(job), false), ids).stream().map(passage -> new JobPassage(passage.id(),
                passage.tier().word(), passage.text(), passage.sectionPath(), passage.page())).toList();
    }

    @Override
    public void events(JobRef job, JobEventBatch batch) {
        TurnRow turn = turnOf(job);
        if (turn.stoppedAt() != null) {
            log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("turnId", turn.id())
                    .addKeyValue("claimId", batch.claimId()).addKeyValue("batch", batch.number())
                    .addKeyValue("events", batch.events().size()).log("conversation.batch_after_stop");
            return;
        }
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
        List<TurnEvent> kept = withTiersInScope(turn, events);
        if (kept.size() < events.size()) {
            log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("turnId", turn.id())
                    .addKeyValue("batch", batch.number()).addKeyValue("dropped", events.size() - kept.size())
                    .log("conversation.tiers_dropped");
        }
        append(turn, batch.claimId(), batch.number(), kept);
    }

    @Override
    public boolean delivered(JobRef job) {
        return store.turnOfJob(job.id()).map(turn -> turn.stoppedAt() != null || store.hasPostedBatch(turn.id()))
                .orElse(false);
    }

    @Override
    public boolean stopRequested(JobRef job) {
        return store.turnOfJob(job.id()).map(turn -> turn.stoppedAt() != null).orElse(false);
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        TurnRow turn = turnOf(job);
        if (ended(turn, "completed")) {
            return;
        }
        storeReferences(turn);
        append(turn, null, null, List.of(new TurnEvent(0, TurnEvent.DONE, null, null, null, null, false, null)));
    }

    /**
     * The GM's stop of the turn (Story 2.6), in the transaction of {@link Conversations#stop}, which holds the job's
     * lock: unless the turn has a final event, its references, {@code done} marked {@code stopped} and
     * {@code stopped_at}. False when the turn had ended already: nothing changes then.
     */
    boolean stop(TurnRow turn) {
        if (ended(turn, "stop")) {
            return false;
        }
        storeReferences(turn);
        append(turn, null, null, List.of(new TurnEvent(0, TurnEvent.DONE, null, null, null, null, true, null)));
        if (!store.markStopped(turn.id(), clock.instant().truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalStateException("conversations.turn_missing");
        }
        return true;
    }

    /** Whether the turn's last stored event is a final one ({@code done} or {@code error}); logs {@code by} when so. */
    private boolean ended(TurnRow turn, String by) {
        long last = store.lastSequence(turn.id());
        boolean ended = last > 0 && conversations.storedEvents(turn.id(), last - 1).stream().anyMatch(TurnEvent::last);
        if (ended) {
            log.atInfo().addKeyValue("jobId", turn.jobId()).addKeyValue("turnId", turn.id()).addKeyValue("by", by)
                    .log("conversation.turn_already_ended");
        }
        return ended;
    }

    /** The turn's citations become its stored references, as the class says. */
    private void storeReferences(TurnRow turn) {
        List<UUID> cited = conversations.storedEvents(turn.id(), 0).stream()
                .filter(event -> TurnEvent.CITATION.equals(event.type())).map(TurnEvent::passageId)
                .filter(Objects::nonNull).distinct().toList();
        List<CitedPlace> places = cited.isEmpty() ? List.of() : scopes.references(scopeOf(turn, false), cited);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<TurnReferenceRow> rows = new ArrayList<>();
        for (TurnReferences.Grouped grouped : TurnReferences.group(places)) {
            UUID rowId = Ids.newId();
            CitedPlace place = grouped.place();
            rows.add(new TurnReferenceRow(rowId, turn.ownerId(), turn.id(), grouped.number(), place.versionId(),
                    place.materialId(), place.page(), grouped.passageIds(),
                    vault.encryptText(TurnReferences.materialName(rowId, turn.ownerId()), place.materialName()),
                    vault.encrypt(TurnReferences.sectionPath(rowId, turn.ownerId()),
                            TurnReferences.pathJson(place.sectionPath())), now, place.tier().word()));
        }
        if (!rows.isEmpty()) {
            references.insertReferences(rows);
        }
        log.atInfo().addKeyValue("jobId", turn.jobId()).addKeyValue("turnId", turn.id())
                .addKeyValue("cited", cited.size()).addKeyValue("dropped", cited.size() - places.size())
                .addKeyValue("references", rows.size()).addKeyValue("silent", rows.isEmpty())
                .log("conversation.references_stored");
    }

    @Override
    public void failed(JobRef job, String code) {
        store.turnOfJob(job.id()).filter(turn -> !ended(turn, "failed")).ifPresent(turn ->
                append(turn, null, null, List.of(new TurnEvent(0, TurnEvent.ERROR, null, null, null, code, false,
                        null))));
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

    /**
     * The turn's scope, under the settings frozen with it (Story 3.5: the stored settings as the question was asked,
     * so a change made meanwhile applies from the next question): with its pending passages for a question input;
     * without them ({@link Scopes#resolveSources}) for the passages and references reads, which check only the allowed
     * sources and tiers.
     */
    private ResolvedScope scopeOf(TurnRow turn, boolean withPending) {
        // Any state: a job call already under way when its conversation turned deleting ends normally (Story 3.10
        // part B); the delete's cancel ends the claim, so no later call of the job comes.
        ConversationRow conversation = store.findConversationAnyState(turn.conversationId())
                .orElseThrow(() -> new IllegalStateException("conversations.conversation_missing"));
        ScopeSettings settings = new ScopeSettings(turn.campaignTier(), turn.systemTier(), turn.otherTier(),
                turn.modelKnowledge(), turn.materialsOff());
        return withPending ? scopes.resolve(conversation.campaignId(), settings)
                : scopes.resolveSources(conversation.campaignId(), settings);
    }

    /**
     * The events without the tier markers whose tier has no source in the turn's frozen scope (Story 3.7): the tier
     * shown is always the backend's, so a marker the worker could not have been handed is never stored or relayed. A
     * {@code model} marker (Story 3.8) is kept only when the turn's frozen model-knowledge switch is on.
     */
    private List<TurnEvent> withTiersInScope(TurnRow turn, List<TurnEvent> events) {
        if (events.stream().noneMatch(event -> TurnEvent.TIER.equals(event.type()))) {
            return events;
        }
        boolean scoped = events.stream().anyMatch(event -> TurnEvent.TIER.equals(event.type())
                && !TurnEvent.MODEL.equals(event.tier()));
        ResolvedScope scope = scoped ? scopeOf(turn, false) : null;
        return events.stream().filter(event -> keeps(turn, scope, event)).toList();
    }

    /** Whether {@link #withTiersInScope} keeps the event; {@code scope} is null only when no event needs it. */
    private static boolean keeps(TurnRow turn, ResolvedScope scope, TurnEvent event) {
        if (!TurnEvent.TIER.equals(event.type())) {
            return true;
        }
        if (TurnEvent.MODEL.equals(event.tier())) {
            return turn.modelKnowledge();
        }
        return scope != null && Tier.of(event.tier()).map(tier -> !scope.sources(tier).isEmpty()).orElse(false);
    }

    private static TurnEvent turnEvent(JobEvent event) {
        return switch (event.type()) {
            case JobEvent.DELTA -> new TurnEvent(0, TurnEvent.DELTA, event.text(), null, null, null, false, null);
            case JobEvent.TIER -> new TurnEvent(0, TurnEvent.TIER, null, null, event.offset(), null, false,
                    event.tier());
            default -> new TurnEvent(0, TurnEvent.CITATION, null, event.passageId(), event.offset(), null, false,
                    null);
        };
    }
}
