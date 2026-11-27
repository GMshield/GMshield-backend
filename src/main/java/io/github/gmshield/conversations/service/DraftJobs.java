package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.ConversationRow;
import io.github.gmshield.conversations.data.ConversationStore;
import io.github.gmshield.conversations.data.TurnRow;
import io.github.gmshield.jobs.service.JobEventBatch;
import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobPassage;
import io.github.gmshield.jobs.service.JobRecord;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobResultInvalid;
import io.github.gmshield.jobs.service.JobTerms;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The job of kind {@value #KIND} (Story 5.3 part A, internal contract 1.25.0): a draft the GM asked for in the
 * questions area (an encounter, an item, a creature, a character sheet...), written by the worker from the campaign's
 * own sources, on the interactive lane, calling a model.
 *
 * <p>A draft is a turn ({@link TurnKind#DRAFT}), so this handler is the answer's ({@link AnswerJobs}) under another
 * kind: the same input (the question input within the turn's frozen scope, with the conversation's history), the same
 * passages, records and terms reads within that scope, the same numbered event batches, tier rules, outcome and
 * references, and the same stop. The worker tells the two apart by the kind alone. Nothing is written to the library:
 * the draft's save is Story 5.4.
 *
 * <p>Story 5.3 part B (internal contract 1.26.0), the draft's proposal:
 * <ul>
 *   <li>{@link #input} adds {@code recordTypeNames}: each key of the input's {@code recordTypes} with the name of the
 *       campaign's system's type ({@link Scopes#recordTypeNames}), data for the model that proposes the draft's
 *       type.</li>
 *   <li>{@link #completed} reads {@code result.proposal {name, recordType}}. A missing result or proposal is accepted
 *       with none (part A's worker reports none). A proposal that is not an object, a name that is not a string or,
 *       once trimmed, is blank or longer than {@value #NAME_MAX} code points, or a record type that is neither a string
 *       nor null, is
 *       refused ({@link JobResultInvalid}: {@code job.result_invalid}, nothing changes). A record type that is not a key
 *       of the campaign's system now ({@link Scopes#recordTypeKeys}; removed since the claim, say) is stored as null.
 *       Only when the turn has not ended and was not stopped, the proposal is stored ({@code markProposed}, the name
 *       trimmed and encrypted under {@link Conversations#proposalName}) in the transaction that appends {@code done},
 *       before it.</li>
 * </ul>
 * Lines carry ids, counts and a core type key only, never the request, the draft, the proposed name or an approved
 * key.
 */
@Service
public class DraftJobs implements JobHandler {

    public static final String KIND = "content.draft";
    /** The most code points a proposed name has (Story 5.3 part B), as an artifact's name. */
    public static final int NAME_MAX = 120;
    private static final String PROPOSAL_INVALID = "conversations.proposal_invalid";
    private static final Logger log = LoggerFactory.getLogger(DraftJobs.class);

    private final AnswerJobs answers;
    private final ConversationStore store;
    private final Scopes scopes;
    private final Vault vault;

    DraftJobs(AnswerJobs answers, ConversationStore store, Scopes scopes, Vault vault) {
        this.answers = answers;
        this.store = store;
        this.scopes = scopes;
        this.vault = vault;
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
    public boolean callsModel(JobRef job) {
        return answers.callsModel(job);
    }

    @Override
    public JsonNode input(JobRef job) {
        JsonNode input = answers.input(job);
        if (!(input instanceof ObjectNode object)) {
            return input;
        }
        List<String> keys = new ArrayList<>();
        object.path("recordTypes").forEach(key -> keys.add(key.asString()));
        Map<String, String> names = scopes.recordTypeNames(campaignOf(turnOf(job)), keys);
        ObjectNode recordTypeNames = object.putObject("recordTypeNames");
        names.forEach(recordTypeNames::put);
        return object;
    }

    @Override
    public List<JobPassage> passages(JobRef job, List<UUID> ids) {
        return answers.passages(job, ids);
    }

    @Override
    public List<JobRecord> records(JobRef job, List<UUID> ids) {
        return answers.records(job, ids);
    }

    @Override
    public JobTerms terms(JobRef job) {
        return answers.terms(job);
    }

    @Override
    public void events(JobRef job, JobEventBatch batch) {
        answers.events(job, batch);
    }

    @Override
    public boolean delivered(JobRef job) {
        return answers.delivered(job);
    }

    @Override
    public boolean stopRequested(JobRef job) {
        return answers.stopRequested(job);
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        Proposed proposed = proposalOf(result);
        if (proposed == null) {
            answers.completed(job, result);
            return;
        }
        answers.completed(job, turn -> store(job, turn, proposed));
    }

    /** A proposal as the worker reported it, checked but for its type's key. */
    private record Proposed(String name, String recordType) {
        @Override
        public String toString() {
            return "Proposed[recordType=" + Scopes.loggedType(recordType) + "]";
        }
    }

    /** The result's proposal, as the class says; null when there is none, {@link JobResultInvalid} when malformed. */
    private static Proposed proposalOf(JsonNode result) {
        if (result == null || result.isNull() || !result.has("proposal")) {
            return null;
        }
        JsonNode proposal = result.get("proposal");
        if (!proposal.isObject()) {
            throw new JobResultInvalid(PROPOSAL_INVALID);
        }
        JsonNode name = proposal.path("name");
        String stripped = name.isString() ? name.asString().strip() : "";
        if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length()) > NAME_MAX) {
            throw new JobResultInvalid(PROPOSAL_INVALID);
        }
        JsonNode type = proposal.path("recordType");
        if (!type.isMissingNode() && !type.isNull() && !type.isString()) {
            throw new JobResultInvalid(PROPOSAL_INVALID);
        }
        return new Proposed(stripped, type.isString() ? type.asString() : null);
    }

    /** Stores the proposal on the turn, its type null unless a key of the campaign's system now. */
    private void store(JobRef job, TurnRow turn, Proposed proposed) {
        String type = proposed.recordType() != null
                && scopes.recordTypeKeys(campaignOf(turn)).contains(proposed.recordType())
                ? proposed.recordType() : null;
        byte[] name = vault.encryptText(Conversations.proposalName(turn.id(), turn.ownerId()), proposed.name());
        if (!store.markProposed(turn.id(), name, type)) {
            throw new IllegalStateException("conversations.turn_missing");
        }
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("turnId", turn.id())
                .addKeyValue("recordType", Scopes.loggedType(type))
                .addKeyValue("typeDropped", proposed.recordType() != null && type == null)
                .addKeyValue("nameLength", proposed.name().codePointCount(0, proposed.name().length()))
                .log("conversation.proposal_stored");
    }

    private TurnRow turnOf(JobRef job) {
        return store.turnOfJob(job.id()).orElseThrow(() -> new IllegalStateException("conversations.turn_missing"));
    }

    private UUID campaignOf(TurnRow turn) {
        // Any state, as the answer's scope: a call under way when the conversation turned deleting ends normally.
        return store.findConversationAnyState(turn.conversationId()).map(ConversationRow::campaignId)
                .orElseThrow(() -> new IllegalStateException("conversations.conversation_missing"));
    }

    @Override
    public void failed(JobRef job, String code) {
        answers.failed(job, code);
    }
}
