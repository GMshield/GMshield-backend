package io.github.gmshield.conversations.service;

import io.github.gmshield.jobs.service.JobEventBatch;
import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobPassage;
import io.github.gmshield.jobs.service.JobRecord;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobTerms;
import io.github.gmshield.jobs.service.Lane;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The job of kind {@value #KIND} (Story 5.3 part A, internal contract 1.25.0): a draft the GM asked for in the
 * questions area (an encounter, an item, a creature, a character sheet...), written by the worker from the campaign's
 * own sources, on the interactive lane, calling a model.
 *
 * <p>A draft is a turn ({@link TurnKind#DRAFT}), so this handler is the answer's ({@link AnswerJobs}) under another
 * kind: the same input (the question input within the turn's frozen scope, with the conversation's history), the same
 * passages, records and terms reads within that scope, the same numbered event batches, tier rules, outcome and
 * references, and the same stop. The worker tells the two apart by the kind alone. Nothing is written to the library:
 * the draft's proposal and its save are later stories.
 */
@Service
public class DraftJobs implements JobHandler {

    public static final String KIND = "content.draft";

    private final AnswerJobs answers;

    DraftJobs(AnswerJobs answers) {
        this.answers = answers;
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
        return answers.input(job);
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
        answers.completed(job, result);
    }

    @Override
    public void failed(JobRef job, String code) {
        answers.failed(job, code);
    }
}
