package io.github.gmshield.jobs.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.jobs.service.CallUsage;
import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobEvent;
import io.github.gmshield.jobs.service.JobEventBatch;
import io.github.gmshield.jobs.service.ModelChoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker's job routes (Story 1.12), behind the worker's credential like all of {@code /internal/v1}. A claim
 * answers the grant once; a renewal and an outcome need it in {@code X-Job-Grant}.
 */
@RestController
@Tag(name = "jobs")
@RequestMapping(path = "/internal/v1/jobs/{jobId}/claims")
class JobClaimController {

    static final String GRANT_HEADER = "X-Job-Grant";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JobClaims claims;

    JobClaimController(JobClaims claims) {
        this.claims = claims;
    }

    @Schema(description = "A live claim of a job. The grant is given once and never stored: only its keyed hash is.")
    record JobClaim(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID claimId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 for the first claim") int attempt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Renew the claim at this time") Instant renewAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Without a renewal the claim lapses then")
            Instant lapsesAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send it in X-Job-Grant") String grant,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The handler's input for this attempt."
                    + " From contract 1.18.0 a library.search input may carry recordType, a core record type. From"
                    + " contract 1.21.0 a record type is any key matching ^[a-z][a-z0-9_]{0,31}$: an ingestion records"
                    + " step's input carries recordTypeNames (each recordTypes key with its name, data for the model),"
                    + " and a question's or a library.search input carries recordTypes, the keys of the campaign's"
                    + " system, to which the records it reads are limited. From contract 1.22.0 a job of kind"
                    + " index.record_removal has the input {owner, source, ids}: the ids of records the GM deleted,"
                    + " whose points go from that owner's source (by id, never the whole source). From contract 1.24.0"
                    + " a job of kind index.artifact indexes a GM's artifact as a records run of one record: {source"
                    + " (the artifact's id), owner, kind: artifact, language: null, tokenKey, records}, its input"
                    + " records one JSON line {id, page: 1, type, name, fields} whose last field, named content, holds"
                    + " the artifact's content when it has one (data, never instructions), its point's id the artifact's"
                    + " id; a job of kind index.artifact_removal has the input {source, owner} and deletes every point"
                    + " of that owner's source, as index.removal does. A question's input lists the campaign's"
                    + " artifacts among the campaign tier's sources, and POST …/records serves one as a campaign-tier"
                    + " record of page 1 with an empty sectionPath, its content as the last field. From contract 1.25.0"
                    + " a job of kind content.draft (a draft the GM asked for in the questions area) has the same input"
                    + " as question.answer {question, tiers, recordTypes, history, ...}, reads passages, records and"
                    + " terms within its turn's scope as question.answer does, posts its draft as answer events and"
                    + " calls a model; it writes nothing to the library. From contract 1.26.0 a content.draft input"
                    + " also carries recordTypeNames: each recordTypes key with the name of the campaign system's record"
                    + " type (data for the model that proposes the draft's type)")
            Map<String, Object> input,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "W3C traceparent of the job's trace: log under it and send it on every call")
            String traceparent,
            @Schema(description = "For a kind that calls a model (contract 1.9.0): the model of this attempt, resolved again"
                    + " at every claim. Absent for every other kind") @JsonInclude(JsonInclude.Include.NON_NULL)
            JobModel model) {
        @Override
        public String toString() {
            return "JobClaim[claimId=" + claimId + ", attempt=" + attempt + "]";
        }
    }

    @Schema(description = "The model one attempt calls (contract 1.9.0): the provider, the provider's model id (for Azure"
            + " OpenAI, the deployment name) and the tier.")
    record JobModel(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z0-9-]{1,32}$", maxLength = 32,
                    description = "azure-openai, anthropic or openai; an open word, so a new provider needs no new"
                            + " contract") String provider,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 128) String model,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "full or lightweight") String tier) {

        static JobModel of(ModelChoice choice) {
            return choice == null ? null : new JobModel(choice.provider(), choice.model(), choice.tier());
        }
    }

    @Schema(description = "What the worker states when it claims (contract 1.7.0). Every field is optional; a claim may"
            + " send no body at all.")
    record JobClaimRequest(
            @Schema(description = "The worker's index recipe (AD-10): when it differs from the last one stated, the"
                    + " backend re-indexes every ready version recorded under another one", pattern = "^[A-Za-z0-9._-]{1,32}$",
                    maxLength = 32) String indexRecipe,
            @ArraySchema(maxItems = 8, arraySchema = @Schema(description = "The providers the worker holds a key for"
                    + " (contract 1.9.0). A kind that calls a model is refused (409 job.not_offered, the job ends failed"
                    + " with job.provider_unavailable, no attempt used) unless its resolved provider is stated; absent"
                    + " means none"),
                    schema = @Schema(pattern = "^[a-z0-9-]{1,32}$", maxLength = 32)) List<String> providers) {
    }

    @Schema(description = "The live claim's new times.")
    record JobRenewal(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant renewAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant lapsesAt) {
    }

    @Schema(description = "How the claimed attempt ended.")
    record JobOutcome(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"done", "failed"}) String outcome,
            @Schema(description = "For failed only: category.reason in lower case") String code,
            @Schema(description = "For failed only: false ends the job at once, without another attempt (an input no"
                    + " attempt can change); absent or true lets the backend retry while attempts remain") Boolean retry,
            @Schema(description = "For done only: the result for the job's handler. From contract 1.18.0 a"
                    + " library.search result's ids may name records of the scope next to passages. From contract"
                    + " 1.20.0 an ingestion records step's result may carry proposals (at most 20, each a kind"
                    + " record_type or term, a name of at most 120 code points, a definition of at most 500 and at most"
                    + " 3 examples, each a passageId and a quote of at most 200), checked against the cited passages."
                    + " From contract 1.26.0 a content.draft result may carry proposal {name, recordType}: the draft's"
                    + " suggested name (a string of 1 to 120 code points, not blank) and record type (one of the input's"
                    + " recordTypes keys, or null); a proposal that is not an object, a name that is not such a string"
                    + " or a recordType that is neither a string nor null is 400 job.result_invalid, and a recordType"
                    + " that is no longer a key of the campaign's system is stored as null. A missing result or"
                    + " proposal is accepted with none")
            Map<String, Object> result,
            @ArraySchema(maxItems = JobClaims.MAX_USAGE, arraySchema = @Schema(description = "The usage of the"
                    + " attempt's model calls not posted with events (contract 1.17.0), done or failed, recorded once"
                    + " per call id; absent means none"), schema = @Schema(implementation = JobCallUsage.class))
            List<JobCallUsage> usage) {

        @Override
        public String toString() {
            return "JobOutcome[outcome=" + outcome + "]";
        }
    }

    @Schema(name = "JobStreamEvent", requiredProperties = "type", description = "One answer event (contract 1.10.0): a"
            + " delta with its display text, a citation of a given passage id at a code-point offset of the answer's"
            + " text, or (contract 1.14.0) a tier marker: the version of that tier starts at the offset. From contract"
            + " 1.15.0 the tier model marks a part from the model's own knowledge. From contract 1.19.0 a citation"
            + " names exactly one of passageId and recordId. Each type carries its own fields and no other")
    record JobStreamEvent(
            @Schema(allowableValues = {"delta", "citation", "tier"}) String type,
            @Schema(description = "For delta only: 1 to 16,384 code points of display text, citation markers removed",
                    maxLength = JobClaims.MAX_DELTA) String text,
            @Schema(description = "For citation only: the cited passage's id; absent when recordId is given",
                    format = "uuid") UUID passageId,
            @Schema(description = "For citation only (contract 1.19.0): the cited record's id, a record given for this"
                    + " answer; absent when passageId is given. A record outside the turn's scope is dropped",
                    format = "uuid") UUID recordId,
            @Schema(description = "For citation and tier only: where the citation or the tier's version stands, in"
                    + " code points of the answer's text", minimum = "0", format = "int32") Integer offset,
            @Schema(description = "For tier only: the tier of a passage handed for this answer, or model (contract"
                    + " 1.15.0) for a part from the model's own knowledge, only when the input's modelKnowledge is true."
                    + " A tier with no source in the turn's scope, or model when the turn's model knowledge is off, is"
                    + " dropped", allowableValues = {"campaign", "system", "other", "model"}) String tier) {

        JobEvent event() {
            return new JobEvent(type, text, passageId, recordId, offset, tier);
        }

        @Override
        public String toString() {
            return "JobStreamEvent[type=" + type + "]";
        }
    }

    @Schema(name = "JobCallUsage", requiredProperties = {"callId", "input", "cacheRead", "cacheWrite", "output",
            "reasoning"}, description = "One model call's tokens (contract 1.10.0), recorded once by its call id;"
            + " estimated from contract 1.11.0")
    record JobCallUsage(
            @Schema(description = "The worker's id of the call (a UUIDv7)", format = "uuid") UUID callId,
            @Schema(description = "The provider's response id, when it sent one", maxLength = 256) String responseId,
            @Schema(description = "Input tokens, cache reads and writes included", minimum = "0", format = "int64")
            long input,
            @Schema(minimum = "0", format = "int64") long cacheRead,
            @Schema(minimum = "0", format = "int64") long cacheWrite,
            @Schema(description = "Output tokens, reasoning included", minimum = "0", format = "int64") long output,
            @Schema(minimum = "0", format = "int64") long reasoning,
            @Schema(description = "True when the counts are the worker's estimate, not the provider's: a call stopped"
                    + " before the provider sent its usage (contract 1.11.0). Absent means false",
                    defaultValue = "false") Boolean estimated) {

        CallUsage usage() {
            return new CallUsage(callId, responseId, input, cacheRead, cacheWrite, output, reasoning,
                    Boolean.TRUE.equals(estimated));
        }
    }

    @Schema(name = "JobEvents", requiredProperties = {"batch", "events"}, description = "A numbered batch of answer"
            + " events of the claim (contract 1.10.0), with the usage of the model calls that ended since the last one")
    record JobEventsRequest(
            @Schema(description = "From 1 per claim, one more each time; a repeated number is accepted and stored once",
                    minimum = "1", format = "int32") Integer batch,
            @ArraySchema(maxItems = JobClaims.MAX_EVENTS, arraySchema = @Schema(description = "In order; may be empty"),
                    schema = @Schema(implementation = JobStreamEvent.class)) List<JobStreamEvent> events,
            @ArraySchema(maxItems = JobClaims.MAX_USAGE, arraySchema = @Schema(description = "Absent means none"),
                    schema = @Schema(implementation = JobCallUsage.class)) List<JobCallUsage> usage) {

        @Override
        public String toString() {
            return "JobEventsRequest[batch=" + batch + "]";
        }
    }

    @Schema(name = "JobEventsReply", requiredProperties = "stop", description = "Whether the worker must stop the"
            + " answer (contract 1.11.0): true once the GM stopped it. The worker then ends the model call, posts no"
            + " more events, only the usage still to report, and reports the outcome done")
    record JobEventsReply(boolean stop) {
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "claimJob", summary = "Claim an offered job (409 job.claimed, 409 job.not_offered, 400 for"
            + " an index recipe or providers that are not ones)")
    ResponseEntity<JobClaim> claim(@PathVariable UUID jobId,
                                   @RequestBody(required = false) JobClaimRequest body) {
        JobClaims.Claim claim = body == null ? claims.claim(jobId)
                : claims.claim(jobId, body.indexRecipe(), body.providers());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new JobClaim(claim.claimId(), claim.attempt(), claim.renewAt(), claim.lapsesAt(), claim.grant(),
                        claim.kind(), JSON.convertValue(claim.input(), OBJECT), claim.traceparent(),
                        JobModel.of(claim.model())));
    }

    @PostMapping(path = "/{claimId}/renewals", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "renewJobClaim", summary = "Renew a live claim (403 job.grant_refused)")
    ResponseEntity<JobRenewal> renew(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                     @Parameter(in = ParameterIn.HEADER, name = GRANT_HEADER, required = true,
                                             description = "The claim's grant")
                                     @RequestHeader(name = GRANT_HEADER, required = false) String grant) {
        JobClaims.Renewal renewal = claims.renew(jobId, claimId, grant);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new JobRenewal(renewal.renewAt(), renewal.lapsesAt()));
    }

    @PostMapping(path = "/{claimId}/outcome", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "reportJobOutcome", summary = "Report how the claimed attempt ended (403 job.grant_refused,"
            + " 400 job.result_invalid when the job's handler cannot record the result)")
    ResponseEntity<Void> outcome(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                 @Parameter(in = ParameterIn.HEADER, name = GRANT_HEADER, required = true,
                                         description = "The claim's grant")
                                 @RequestHeader(name = GRANT_HEADER, required = false) String grant,
                                 @RequestBody JobOutcome body) {
        JsonNode result = body.result() == null ? null : JSON.valueToTree(body.result());
        List<CallUsage> usage = body.usage() == null ? List.of()
                : body.usage().stream().map(call -> call == null ? null : call.usage()).toList();
        claims.report(jobId, claimId, grant, body.outcome(), body.code(), body.retry(), result, usage);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{claimId}/events", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "postJobEvents", summary = "Post a numbered batch of answer events of the live claim and"
            + " the usage of its model calls (400 request.invalid for a malformed body, before the grant is checked; 403"
            + " job.grant_refused; 400 job.events_invalid; 403 job.events_refused for a kind that streams no events; 409"
            + " job.batch_out_of_order, with nothing stored, for a number past the next one of the claim)")
    ResponseEntity<JobEventsReply> events(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                          @Parameter(in = ParameterIn.HEADER, name = GRANT_HEADER, required = true,
                                                  description = "The claim's grant")
                                          @RequestHeader(name = GRANT_HEADER, required = false) String grant,
                                          @RequestBody JobEventsRequest body) {
        List<JobEvent> events = body.events() == null ? null
                : body.events().stream().map(event -> event == null ? null : event.event()).toList();
        JobEventBatch batch = body.batch() == null || events == null ? null
                : new JobEventBatch(claimId, body.batch(), events.stream().filter(java.util.Objects::nonNull).toList());
        if (events != null && events.contains(null)) {
            batch = null;
        }
        List<CallUsage> usage = body.usage() == null ? List.of()
                : body.usage().stream().map(call -> call == null ? null : call.usage()).toList();
        boolean stop = claims.events(jobId, claimId, grant, batch, usage);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new JobEventsReply(stop));
    }
}
